package com.example.shelfplayer.feature.metadata

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ManagementAction
import com.example.shelfplayer.core.model.ManagementBlock
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataError
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.CoverRejection
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
        CoverSection(state, viewModel)
        MatchAndScanSection(state, viewModel)
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
 * PRODUCT_SPEC MGR-003 / MGR-004 — ask a provider what it knows, and ask the server to re-read the files.
 *
 * Both live in the editor rather than in the book screen's menu, and for the same reason: what they produce
 * is *proposed metadata*, and the place to review proposed metadata is the form that already shows all of
 * it. A match applied from a menu would change fields on a screen that does not display them.
 */
@Composable
private fun MatchAndScanSection(state: EditMetadataUiState, viewModel: EditMetadataViewModel) {
    val permissions = state.permissions
    val canMatch = permissions?.isAvailable(ManagementAction.MatchMetadata) == true && !state.isSaving
    val canScan = permissions?.isAvailable(ManagementAction.ScanItem) == true && !state.isSaving

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { viewModel.findMatches(DEFAULT_PROVIDER) }, enabled = canMatch && !state.isMatching) {
            Text(stringResource(R.string.edit_metadata_match))
        }
        TextButton(onClick = viewModel::scan, enabled = canScan && !state.isScanning) {
            Text(stringResource(R.string.edit_metadata_scan))
        }
    }
    // The server's own word, shown verbatim rather than translated into a sentence. `UPTODATE` and
    // `NOTHING` mean different things to somebody debugging an import, and inventing a friendlier phrase
    // for each would be guessing at what a future server means by a word this build has not seen.
    state.scanResult?.let { result ->
        Text(
            stringResource(R.string.edit_metadata_scan_result, result),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.candidates.isNotEmpty() || state.isMatchSheetOpen) CandidateSheet(state, viewModel)
}

/**
 * PRODUCT_SPEC MGR-003 — the candidates, and the fields each would change.
 *
 * ### Two things here are safety rather than presentation
 *
 * The **description is never rendered as markup**. It is provider-supplied HTML, and Compose's `Text` draws
 * it as characters, which is exactly the sanitisation the requirement asks for — the tags show as tags
 * rather than executing as formatting, and nothing can smuggle a link through.
 *
 * The **cover URL is not fetched**. It points at Google or Audible, and this app's image loading carries
 * the server's `Authorization` header — sending that to a third party is what MGR-002's "tokens are not
 * appended to third-party cover URLs" forbids. The candidate is chosen on its text, and the cover arrives
 * later from the user's own server after the match is saved and the server has downloaded it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateSheet(state: EditMetadataUiState, viewModel: EditMetadataViewModel) {
    ModalBottomSheet(onDismissRequest = viewModel::dismissMatches) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.edit_metadata_match_from, state.matchProvider),
                style = MaterialTheme.typography.titleMedium,
            )
            // PRODUCT_SPEC MGR-003 — the other sources, offered rather than assumed. A deployment that
            // cannot reach Google can usually reach Audible, and only the server knows which it has.
            if (state.providers.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.providers.forEach { provider ->
                        FilterChip(
                            selected = provider.id == state.matchProvider,
                            onClick = { viewModel.findMatches(provider.id) },
                            label = { Text(provider.displayName) },
                        )
                    }
                }
            }
            if (state.candidates.isEmpty() && !state.isMatching) {
                Text(
                    stringResource(R.string.edit_metadata_match_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val selected = state.selectedCandidate
            if (selected == null) {
                state.candidates.forEach { candidate ->
                    ListItem(
                        headlineContent = { Text(candidate.title) },
                        supportingContent = {
                            Text(
                                listOfNotNull(candidate.author, candidate.publishedYear)
                                    .joinToString(" · ")
                                    .ifEmpty { stringResource(R.string.edit_metadata_match_unknown) },
                            )
                        },
                        modifier = Modifier.clickable { viewModel.selectCandidate(candidate) },
                    )
                }
            } else {
                Text(stringResource(R.string.edit_metadata_match_changes), style = MaterialTheme.typography.bodyMedium)
                selected.changesAgainst(state.form).forEach { field ->
                    ListItem(
                        headlineContent = { FIELD_LABELS[field]?.let { label -> Text(stringResource(label)) } },
                        trailingContent = {
                            Checkbox(
                                checked = field in state.acceptedFields,
                                onCheckedChange = { viewModel.toggleAcceptedField(field) },
                            )
                        },
                    )
                }
                TextButton(onClick = viewModel::applyCandidate, enabled = state.acceptedFields.isNotEmpty()) {
                    Text(stringResource(R.string.edit_metadata_match_apply))
                }
            }
        }
    }
}

/**
 * PRODUCT_SPEC MGR-002 — pick, preview, commit; and remove, with a confirmation.
 *
 * ### The preview is the picked image, not the stored one
 *
 * MGR-002 asks for a preview *before* commit, so what is drawn between picking and confirming is the bytes
 * in memory — not a re-fetch of the server's copy, which is still the old cover until the upload lands.
 *
 * ### Two permissions, two buttons
 *
 * *Change* needs update **and** upload; *remove* needs delete. The server gates them differently, so an
 * account can genuinely have one and not the other, and collapsing them into one "may edit covers" flag
 * would offer an action that then fails.
 */
@Composable
private fun CoverSection(state: EditMetadataUiState, viewModel: EditMetadataViewModel) {
    val permissions = state.permissions
    val canChange = permissions?.isAvailable(ManagementAction.ChangeCover) == true && !state.isSaving
    val canRemove = permissions?.isAvailable(ManagementAction.RemoveCover) == true && !state.isSaving
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val read = CoverPicker.read(context.contentResolver, uri)) {
            is AppResult.Failure -> viewModel.coverPickFailed(read.error.summary)
            is AppResult.Success -> viewModel.coverPicked(read.value)
        }
    }
    var confirmingRemoval by remember { mutableStateOf(false) }

    Text(stringResource(R.string.edit_metadata_cover), style = MaterialTheme.typography.labelLarge)
    state.pickedCover?.let { picked -> CoverPreview(picked) }
    state.coverRejection?.let { rejection ->
        Text(
            stringResource(rejectionTextOf(rejection)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.pickedCover == null) {
            TextButton(
                onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                enabled = canChange,
            ) { Text(stringResource(R.string.edit_metadata_cover_choose)) }
            TextButton(onClick = { confirmingRemoval = true }, enabled = canRemove) {
                Text(stringResource(R.string.edit_metadata_cover_remove))
            }
        } else {
            TextButton(onClick = viewModel::confirmCover, enabled = canChange) {
                Text(stringResource(R.string.edit_metadata_cover_use))
            }
            TextButton(onClick = viewModel::discardPickedCover) {
                Text(stringResource(R.string.edit_metadata_cover_cancel))
            }
        }
    }

    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text(stringResource(R.string.edit_metadata_cover_remove_title)) },
            // Says what it does *and* what it does not: removing a cover is not deleting the book, and a
            // destructive-sounding button with no scope is how somebody stops trusting the app.
            text = { Text(stringResource(R.string.edit_metadata_cover_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRemoval = false
                        viewModel.removeCover()
                    },
                ) { Text(stringResource(R.string.edit_metadata_cover_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) {
                    Text(stringResource(R.string.edit_metadata_cover_cancel))
                }
            },
        )
    }
}

/**
 * The picked image, decoded once for display.
 *
 * `remember(picked)` so the decode happens on pick rather than on every recomposition — a cover is up to
 * ten megabytes, and re-decoding it while somebody types in the title field below would make the field
 * stutter.
 */
@Composable
private fun CoverPreview(picked: PickedCover) {
    val bitmap = remember(picked) {
        BitmapFactory.decodeByteArray(picked.bytes, 0, picked.bytes.size)?.asImageBitmap()
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.edit_metadata_cover_preview),
            modifier = Modifier.size(PREVIEW_SIZE),
        )
    }
}

private fun rejectionTextOf(rejection: CoverRejection): Int = when (rejection) {
    CoverRejection.UnsupportedType -> R.string.edit_metadata_cover_error_type
    CoverRejection.NotAnImage -> R.string.edit_metadata_cover_error_decode
    CoverRejection.TooLarge -> R.string.edit_metadata_cover_error_large
    CoverRejection.TooSmall -> R.string.edit_metadata_cover_error_small
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

private val PREVIEW_SIZE = 160.dp

/**
 * PRODUCT_SPEC MGR-003 — the provider a match starts with.
 *
 * Google, because it is the server's own default and the only one that needs no configuration. The captured
 * provider list shows fourteen on a bare install, so a picker is a reasonable next step — it is not here
 * because nobody has asked which provider they want, and one that works everywhere beats a choice nobody
 * makes.
 */
private const val DEFAULT_PROVIDER = "google"
