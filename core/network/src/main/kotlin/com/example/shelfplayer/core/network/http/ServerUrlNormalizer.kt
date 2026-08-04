package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-001 / 10.3 — base URL normalization.
 *
 * Rules, each of which corresponds to an acceptance criterion:
 *  - a URL without a scheme is proposed as HTTPS first;
 *  - trailing slashes are removed, but a required subpath is preserved
 *    (`https://host/audiobooks/` must not become `https://host`);
 *  - a query string or fragment on a base URL is a mistake and is rejected rather than silently
 *    dropped, because it usually means the user pasted a deep link containing a token;
 *  - `http://` is preserved when explicitly typed. Whether a cleartext connection is permitted is a
 *    build/policy decision (PRODUCT_SPEC 15), not a parsing decision, so this class reports it via
 *    [NormalizedServerUrl.isCleartext] instead of deciding.
 */
@Singleton
class ServerUrlNormalizer @Inject constructor() {
    fun normalize(input: String): AppResult<NormalizedServerUrl> {
        val trimmed = input.trim()
        val candidate = if (trimmed.contains(SCHEME_SEPARATOR)) trimmed else "$HTTPS_SCHEME://$trimmed"
        val parsed = if (trimmed.isEmpty()) null else candidate.toHttpUrlOrNull()

        rejectionFor(trimmed, candidate, parsed)?.let { return it }

        val url = checkNotNull(parsed) { "rejectionFor must reject an unparseable URL" }
        return AppResult.Success(
            NormalizedServerUrl(
                value = render(url),
                isCleartext = url.scheme == HTTP_SCHEME,
                wasSchemeAssumed = !trimmed.contains(SCHEME_SEPARATOR),
            ),
        )
    }

    /**
     * The single decision point for "why is this address not usable?".
     *
     * Collecting the rejections here rather than returning early from [normalize] keeps every reason
     * in one readable table, which is what the AUTH-001 acceptance criteria are.
     */
    private fun rejectionFor(trimmed: String, candidate: String, parsed: HttpUrl?): AppResult.Failure? {
        val (code, summary) = when {
            trimmed.isEmpty() ->
                "required" to "Enter the address of your Audiobookshelf server."

            parsed == null ->
                "malformed" to "That does not look like a server address."

            parsed.querySize > 0 || candidate.contains('#') ->
                "unexpected_query" to "Enter only the server address, without a query string."

            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ->
                "credentials_in_url" to "Enter the server address without a username or password in it."

            else -> return null
        }
        return AppResult.Failure(
            AppError.Validation(summary = summary, fieldErrors = mapOf(FIELD to code)),
        )
    }

    /**
     * Renders the URL without a trailing slash, keeping a non-empty subpath.
     *
     * [HttpUrl] always reports at least one path segment, so the "no subpath" case is the one where
     * every segment is empty.
     */
    private fun render(url: HttpUrl): String {
        val path = url.pathSegments.filter(String::isNotEmpty).joinToString(separator = "/")
        val authority = buildString {
            append(url.scheme)
            append("://")
            append(url.host)
            if (url.port != HttpUrl.defaultPort(url.scheme)) {
                append(':')
                append(url.port)
            }
        }
        return if (path.isEmpty()) authority else "$authority/$path"
    }

    private companion object {
        const val FIELD = "serverUrl"
        const val SCHEME_SEPARATOR = "://"
        const val HTTP_SCHEME = "http"
        const val HTTPS_SCHEME = "https"
    }
}

/**
 * @property value the normalized base URL, without a trailing slash.
 * @property isCleartext `true` when the connection would not be encrypted. PRODUCT_SPEC 15 disables
 *   cleartext in release builds unless the user enables a per-server exception.
 * @property wasSchemeAssumed `true` when the user did not type a scheme and HTTPS was proposed, so
 *   the UI can say so rather than silently changing what the user entered.
 */
data class NormalizedServerUrl(val value: String, val isCleartext: Boolean, val wasSchemeAssumed: Boolean)
