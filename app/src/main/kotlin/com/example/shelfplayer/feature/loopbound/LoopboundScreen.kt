package com.example.shelfplayer.feature.loopbound

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.ui.glass.LocalPlayerChromeBottomInset
import java.io.ByteArrayInputStream

private const val LOOPBOUND_ASSET_DIRECTORY = "loopbound"
private const val LOOPBOUND_ENTRY_FILE = "index.html"
private const val LOOPBOUND_ENTRY_PATH = "$LOOPBOUND_ASSET_DIRECTORY/$LOOPBOUND_ENTRY_FILE"
private const val APP_ASSET_HOST = "appassets.androidplatform.net"
private const val LOOPBOUND_URL = "https://$APP_ASSET_HOST/assets/$LOOPBOUND_ENTRY_PATH"
private const val LOOPBOUND_URL_PATH_PREFIX = "/assets/$LOOPBOUND_ASSET_DIRECTORY/"

/**
 * Hosts the web build of Loopbound as an ordinary BookWave destination.
 *
 * Playback deliberately does not cross this boundary: BookWave's Media3 service and mini player remain
 * the only audiobook playback owners while this screen is visible. Loopbound gets a private, local-only
 * HTTPS-like origin backed by APK assets, which lets its existing IndexedDB save adapter work without a
 * Capacitor shell and without giving the game permission to navigate to arbitrary remote content.
 */
@Composable
fun LoopboundRoute(onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    LoopboundScreen(onNavigateUp = onNavigateUp, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoopboundScreen(onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasBundle = remember(context) { context.hasLoopboundBundle() }
    val playerInset = LocalPlayerChromeBottomInset.current.coerceAtLeast(0.dp)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.loopbound_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (hasBundle) {
            LoopboundWebView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding(), bottom = playerInset),
            )
        } else {
            ShelfEmptyState(
                title = stringResource(R.string.loopbound_not_bundled_title),
                body = stringResource(R.string.loopbound_not_bundled_body),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = playerInset),
            )
        }
    }
}

@Composable
private fun LoopboundWebView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val webView = remember(context) { createLoopboundWebView(context) }

    DisposableEffect(webView) {
        onDispose {
            // Loopbound saves and stops its game clock when the page becomes hidden. Pausing the WebView
            // before destruction gives that lifecycle transition a chance to run without loading any
            // replacement document that could escape the local-only navigation policy below.
            webView.onPause()
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        update = { view ->
            if (view.url == null) {
                view.loadUrl(LOOPBOUND_URL)
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled") // Required by the bundled game; all non-game requests are blocked below.
private fun createLoopboundWebView(context: Context): WebView = WebView(context).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
            context.loadLoopboundAsset(request.url)

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
            !request.url.isLoopboundAssetUrl()

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            view?.destroy()
            return true
        }
    }
}

private fun Context.loadLoopboundAsset(uri: Uri): WebResourceResponse {
    val assetPath = uri.toLoopboundAssetPath() ?: return blockedResponse()
    return runCatching {
        val extension = assetPath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        WebResourceResponse(
            mimeType,
            mimeType.responseEncoding(),
            assets.open(assetPath),
        )
    }.getOrElse {
        notFoundResponse()
    }
}

private fun Uri.toLoopboundAssetPath(): String? {
    if (!isLoopboundAssetUrl()) return null
    val assetPath = path.orEmpty().removePrefix("/assets/")
    if (assetPath.contains('\\')) return null
    val pathSegments = assetPath.split('/')
    if (pathSegments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return assetPath
}

private fun Uri.isLoopboundAssetUrl(): Boolean =
    scheme == "https" && host == APP_ASSET_HOST && path.orEmpty().startsWith(LOOPBOUND_URL_PATH_PREFIX)

private fun Context.hasLoopboundBundle(): Boolean = runCatching {
    assets.open(LOOPBOUND_ENTRY_PATH).use { Unit }
}.isSuccess

private fun String.responseEncoding(): String? =
    if (startsWith("text/") || this == "application/javascript" || this == "application/json" || this == "image/svg+xml") {
        Charsets.UTF_8.name()
    } else {
        null
    }

private fun blockedResponse(): WebResourceResponse = errorResponse(403, "Blocked by BookWave")

private fun notFoundResponse(): WebResourceResponse = errorResponse(404, "Not Found")

private fun errorResponse(statusCode: Int, reasonPhrase: String): WebResourceResponse = WebResourceResponse(
    "text/plain",
    Charsets.UTF_8.name(),
    statusCode,
    reasonPhrase,
    emptyMap(),
    ByteArrayInputStream(ByteArray(0)),
)
