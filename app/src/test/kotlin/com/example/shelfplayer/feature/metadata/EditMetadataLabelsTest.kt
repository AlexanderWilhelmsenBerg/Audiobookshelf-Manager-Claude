package com.example.shelfplayer.feature.metadata

import com.example.shelfplayer.core.model.library.BookMetadataField
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC MGR-001 — every editable field has a label.
 *
 * The exhaustiveness a `when` would have enforced at compile time, restored as a test after the `when`
 * became a map. Without it, adding a field to [BookMetadataField] would leave it silently unnamed in the
 * conflict list — which is precisely the dialogue where a field with no name makes the choice impossible.
 */
class EditMetadataLabelsTest {

    @Test
    fun `every editable field has a label`() {
        val missing = BookMetadataField.entries.filterNot { it in FIELD_LABELS }

        assertTrue(missing.isEmpty(), "no label for: $missing")
    }

    @Test
    fun `no two fields share a label`() {
        assertEquals(FIELD_LABELS.size, FIELD_LABELS.values.distinct().size)
    }
}
