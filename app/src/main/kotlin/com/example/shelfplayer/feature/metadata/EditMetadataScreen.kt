package com.example.shelfplayer.feature.metadata

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.ManagementBlock
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataError
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.SeriesEdit

/** So a test can find the save control without depending on its label. */
internal const val EDIT_METADATA_SAVE = "edit-metadata-save"

/**
 * PRODUCT_SPEC MGR-001 — the metadata editor.
 *
 * ### Lists are edited as comma-separated text
 *
 * Authors, narrators, genres and tags are each one text field whose separator is a comma, rather than a
 * chip editor with an add button. That is a deliberate trade rather than a shortcut: the values are short,
 * the lists are two or three items long, and the whole point of this screen is to fix a name that imported
 * wrong — a task a text field does in one gesture and a chip editor does in four.
 *
 * The cost is a name containing a comma, which cannot be entered. That is real and it is recorded in
 * `docs/gaps.md` rather than hidden here.
 *
 * ### A blocked editor still opens
 *
 * A user without permission sees the fields, filled in and read-only, and a line saying why they cannot be
 * changed. Refusing to open would answer "why is this greyed out" with nothing, and the values are the
 * same ones the book screen already shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMetadataScreen(onBack: () -> Unit, viewModel: EditMetadataViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // MGR-001 — "network failure retains an explicit unsaved draft locally", and leaving the screen is
    // the other moment the text would otherwise be lost. `rememberUpdatedState` so the effect that runs on
    // disposal reads the state as it was when the user left, not as it was when the effect was created.
    val current by rememberUpdatedState(state)
    DisposableEffect(Unit) {
        onDispose { if (current.changed.isNotEmpty()) viewModel.keepDraft() }
    }
    BackHandler(enabled = false) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_metadata_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = state.canSave,
                        modifier = Modifier.testTag(EDIT_METADATA_SAVE),
                    ) {
                        Text(stringResource(R.string.edit_metadata_save))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Column(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator() }
            state.isMissing -> Column(Modifier.fillMaxSize().padding(padding)) {
                Text(stringResource(R.string.edit_metadata_missing), Modifier.padding(16.dp))
            }
            else -> EditMetadataForm(state, viewModel, Modifier.padding(padding))
        }
    }

    state.draft?.let { draft -> DraftPrompt(draft, viewModel) }
    if (state.conflicts.isNotEmpty()) ConflictPrompt(state, viewModel)
}

@Composable
private fun EditMetadataForm(
    state: EditMetadataUiState,
    viewModel: EditMetadataViewModel,
    modifier: Modifier = Modifier,
) {
    val enabled = state.canEdit && !state.isSaving
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.block?.let { block -> BlockedNotice(block) }
        state.errorSummary?.let { summary ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    if (state.hasStoredDraft) {
                        Text(
                            stringResource(R.string.edit_metadata_draft_kept),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Field(R.string.edit_metadata_field_title, state.form.title, enabled, state, BookMetadataField.Title) {
            viewModel.edit { form -> form.copy(title = it) }
        }
        Field(R.string.edit_metadata_field_subtitle, state.form.subtitle, enabled, state, BookMetadataField.Subtitle) {
            viewModel.edit { form -> form.copy(subtitle = it) }
        }
        ListField(R.string.edit_metadata_field_authors, state.form.authors, enabled, state, BookMetadataField.Authors) {
            viewModel.edit { form -> form.copy(authors = it) }
        }
        ListField(
            R.string.edit_metadata_field_narrators,
            state.form.narrators,
            enabled,
            state,
            BookMetadataField.Narrators,
        ) { viewModel.edit { form -> form.copy(narrators = it) } }
        SeriesFields(state, enabled, viewModel)
        ListField(R.string.edit_metadata_field_genres, state.form.genres, enabled, state, BookMetadataField.Genres) {
            viewModel.edit { form -> form.copy(genres = it) }
        }
        ListField(R.string.edit_metadata_field_tags, state.form.tags, enabled, state, BookMetadataField.Tags) {
            viewModel.edit { form -> form.copy(tags = it) }
        }
        Field(
            label = R.string.edit_metadata_field_year,
            value = state.form.publishedYear,
            enabled = enabled,
            state = state,
            field = BookMetadataField.PublishedYear,
            keyboardType = KeyboardType.Number,
        ) { viewModel.edit { form -> form.copy(publishedYear = it) } }
        Field(
            R.string.edit_metadata_field_publisher,
            state.form.publisher,
            enabled,
            state,
            BookMetadataField.Publisher,
        ) {
            viewModel.edit { form -> form.copy(publisher = it) }
        }
        Field(R.string.edit_metadata_field_language, state.form.language, enabled, state, BookMetadataField.Language) {
            viewModel.edit { form -> form.copy(language = it) }
        }
        Field(R.string.edit_metadata_field_isbn, state.form.isbn, enabled, state, BookMetadataField.Isbn) {
            viewModel.edit { form -> form.copy(isbn = it) }
        }
        Field(R.string.edit_metadata_field_asin, state.form.asin, enabled, state, BookMetadataField.Asin) {
            viewModel.edit { form -> form.copy(asin = it) }
        }
        Field(
            label = R.string.edit_metadata_field_description,
            value = state.form.description,
            enabled = enabled,
            state = state,
            field = BookMetadataField.Description,
            singleLine = false,
        ) { viewModel.edit { form -> form.copy(description = it) } }

        ListItem(
            headlineContent = { Text(stringResource(R.string.edit_metadata_field_explicit)) },
            trailingContent = {
                Switch(
                    checked = state.form.isExplicit,
                    enabled = enabled,
                    onCheckedChange = { checked -> viewModel.edit { form -> form.copy(isExplicit = checked) } },
                )
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.edit_metadata_field_abridged)) },
            trailingContent = {
                Switch(
                    checked = state.form.isAbridged,
                    enabled = enabled,
                    onCheckedChange = { checked -> viewModel.edit { form -> form.copy(isAbridged = checked) } },
                )
            },
        )

        if (state.changed.isNotEmpty()) {
            TextButton(onClick = viewModel::revert) { Text(stringResource(R.string.edit_metadata_revert)) }
        }
    }
}

/**
 * PRODUCT_SPEC MGR-001 — series and sequence, one row per membership plus an empty row to add one.
 *
 * The trailing empty row is what makes "add a series" need no button. A row whose name is left blank is
 * dropped on the way to the wire, so an untouched empty row costs nothing.
 */
@Composable
private fun SeriesFields(state: EditMetadataUiState, enabled: Boolean, viewModel: EditMetadataViewModel) {
    val rows = state.form.series + SeriesEdit("", "")
    Text(stringResource(R.string.edit_metadata_field_series), style = MaterialTheme.typography.labelLarge)
    rows.forEachIndexed { index, row ->
        OutlinedTextField(
            value = row.name,
            onValueChange = { name -> viewModel.edit { form -> form.withSeriesAt(index, row.copy(name = name)) } },
            label = { Text(stringResource(R.string.edit_metadata_field_series_name)) },
            enabled = enabled,
            singleLine = true,
            isError = state.fieldErrors.containsKey(BookMetadataField.Series),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = row.sequence,
            onValueChange = { seq -> viewModel.edit { form -> form.withSeriesAt(index, row.copy(sequence = seq)) } },
            label = { Text(stringResource(R.string.edit_metadata_field_series_sequence)) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.fieldErrors[BookMetadataField.Series]?.let { error ->
        Text(errorTextOf(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Replaces or appends a series row, which is what an editable list plus a trailing blank row needs. */
private fun BookMetadataEdit.withSeriesAt(index: Int, row: SeriesEdit): BookMetadataEdit {
    val updated = series.toMutableList()
    if (index in updated.indices) updated[index] = row else updated += row
    return copy(series = updated)
}

@Composable
private fun Field(
    label: Int,
    value: String,
    enabled: Boolean,
    state: EditMetadataUiState,
    field: BookMetadataField,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    val error = state.fieldErrors[field]
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = singleLine,
        isError = error != null,
        supportingText = supportingTextFor(error, field in state.changed),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A list field, as comma-separated text. See the screen comment for why.
 *
 * The split happens on every keystroke, which means a trailing comma produces a blank entry — kept on
 * purpose, so that typing `Ada,` and pausing does not delete the comma the user just typed.
 */
@Composable
private fun ListField(
    label: Int,
    values: List<String>,
    enabled: Boolean,
    state: EditMetadataUiState,
    field: BookMetadataField,
    onChange: (List<String>) -> Unit,
) {
    Field(
        label = label,
        value = values.joinToString(SEPARATOR),
        enabled = enabled,
        state = state,
        field = field,
    ) { text -> onChange(if (text.isEmpty()) emptyList() else text.split(",").map(String::trim)) }
}

@Composable
private fun supportingTextFor(error: BookMetadataError?, isChanged: Boolean): (@Composable () -> Unit)? = when {
    error != null -> {
        { Text(errorTextOf(error), color = MaterialTheme.colorScheme.error) }
    }
    // PRODUCT_SPEC MGR-001 — "dirty fields are tracked", and tracking it invisibly would help nobody.
    isChanged -> {
        { Text(stringResource(R.string.edit_metadata_changed)) }
    }
    else -> null
}

@Composable
private fun errorTextOf(error: BookMetadataError): String = stringResource(
    when (error) {
        BookMetadataError.TitleRequired -> R.string.edit_metadata_error_title
        BookMetadataError.YearNotANumber -> R.string.edit_metadata_error_year
        BookMetadataError.SeriesNameRequired -> R.string.edit_metadata_error_series
    },
)

@Composable
private fun BlockedNotice(block: ManagementBlock) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                when (block) {
                    ManagementBlock.Offline -> R.string.edit_metadata_blocked_offline
                    ManagementBlock.Permission -> R.string.edit_metadata_blocked_permission
                    ManagementBlock.Capability -> R.string.edit_metadata_blocked_capability
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** PRODUCT_SPEC MGR-001 — an unsaved draft is *offered*, never silently applied. */
@Composable
private fun DraftPrompt(draft: BookMetadataEdit, viewModel: EditMetadataViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::discardDraft,
        title = { Text(stringResource(R.string.edit_metadata_draft_title)) },
        text = { Text(stringResource(R.string.edit_metadata_draft_body, draft.title.ifBlank { "—" })) },
        confirmButton = {
            TextButton(onClick = viewModel::applyDraft) {
                Text(stringResource(R.string.edit_metadata_draft_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::discardDraft) {
                Text(stringResource(R.string.edit_metadata_draft_discard))
            }
        },
    )
}

/**
 * PRODUCT_SPEC MGR-001 — "on conflict or stale data, user sees field-level differences and can reload or
 * overwrite when safe".
 *
 * Both words are on the buttons, and the field list is what makes the choice possible: "somebody changed
 * this item" is not a decision anybody can make, and "somebody else changed the title and the description"
 * is.
 */
@Composable
private fun ConflictPrompt(state: EditMetadataUiState, viewModel: EditMetadataViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.edit_metadata_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.edit_metadata_conflict_body))
                state.conflicts.forEach { field ->
                    FIELD_LABELS[field]?.let { label ->
                        Text("• ${stringResource(label)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.save(overwrite = true) }) {
                Text(stringResource(R.string.edit_metadata_conflict_overwrite))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::reload) {
                Text(stringResource(R.string.edit_metadata_conflict_reload))
            }
        },
    )
}

/**
 * PRODUCT_SPEC MGR-001 — the label for each field, for the conflict list.
 *
 * A map rather than a `when`, because a fifteen-branch `when` that only ever returns a constant is a
 * lookup table written the long way. `EditMetadataLabelsTest` asserts every field has an entry, which is
 * the exhaustiveness the `when` would have given.
 */
internal val FIELD_LABELS: Map<BookMetadataField, Int> = mapOf(
    BookMetadataField.Title to R.string.edit_metadata_field_title,
    BookMetadataField.Subtitle to R.string.edit_metadata_field_subtitle,
    BookMetadataField.Authors to R.string.edit_metadata_field_authors,
    BookMetadataField.Narrators to R.string.edit_metadata_field_narrators,
    BookMetadataField.Series to R.string.edit_metadata_field_series,
    BookMetadataField.Genres to R.string.edit_metadata_field_genres,
    BookMetadataField.Tags to R.string.edit_metadata_field_tags,
    BookMetadataField.PublishedYear to R.string.edit_metadata_field_year,
    BookMetadataField.Publisher to R.string.edit_metadata_field_publisher,
    BookMetadataField.Description to R.string.edit_metadata_field_description,
    BookMetadataField.Isbn to R.string.edit_metadata_field_isbn,
    BookMetadataField.Asin to R.string.edit_metadata_field_asin,
    BookMetadataField.Language to R.string.edit_metadata_field_language,
    BookMetadataField.Explicit to R.string.edit_metadata_field_explicit,
    BookMetadataField.Abridged to R.string.edit_metadata_field_abridged,
)

private const val SEPARATOR = ", "
