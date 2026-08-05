package com.example.shelfplayer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * PRODUCT_SPEC LIB-002 / 21 — empty, loading, error and offline states are distinct and each one is
 * announced to TalkBack.
 *
 * They live in the design system rather than in each screen so that "did this screen handle the
 * empty case?" is answerable by looking at which of these it uses.
 */
@Composable
fun ShelfLoadingState(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
fun ShelfEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ShelfMessageState(
        title = title,
        body = body,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

/**
 * PRODUCT_SPEC 14.4 — a user-facing error states what happened, what it means and what to do.
 *
 * [technicalCode] is the optional stable [com.example.shelfplayer.core.model.AppError] code. A stack
 * trace is never shown (PRODUCT_SPEC 14.4).
 */
@Composable
fun ShelfErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    technicalCode: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ShelfMessageState(
        title = title,
        body = body,
        modifier = modifier,
        footnote = technicalCode,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
private fun ShelfMessageState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}
