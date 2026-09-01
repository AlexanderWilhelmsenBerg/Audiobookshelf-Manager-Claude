package com.example.shelfplayer.data.settings.transfer

import com.example.shelfplayer.core.datastore.AppSettings
import org.junit.Test
import java.lang.reflect.Method
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC SET-001 — the guard that makes a hand-written export safe to keep hand-written.
 *
 * [SettingsDocument] mirrors `AppSettings` field by field, which is what makes an exported file readable
 * and what makes the privacy claim checkable by the person whose file it is. The cost of that choice is
 * drift: adding a setting to the proto and forgetting to add it here produces a build that compiles, tests
 * that pass, and a settings file quietly missing a setting. Nothing would ever say so.
 *
 * So this test says so. It reads the *generated message's own accessors* — not a list somebody maintains —
 * and fails until every field is either in `SettingsTransfer.EXPORTED_FIELDS` or named in
 * `EXCLUDED_FIELDS` with the reason it stays behind.
 *
 * A failure here is not a defect to route around. It is one decision to make about one new setting:
 * does it describe the user's preferences, or this install?
 */
class SettingsTransferDriftTest {

    @Test
    fun `every settings field is either exported or deliberately excluded`() {
        val handled = SettingsTransfer.EXPORTED_FIELDS + SettingsTransfer.EXCLUDED_FIELDS.keys
        val missing = protoFields() - handled
        assertEquals(
            emptySet(),
            missing,
            "AppSettings has field(s) the settings export neither carries nor excludes: $missing. " +
                "Add each to SettingsTransfer.EXPORTED_FIELDS and SettingsDocument's SettingsBody, or to " +
                "EXCLUDED_FIELDS with the reason it describes this install rather than the user.",
        )
    }

    /** And the other direction: a name that no longer exists is a mirror of something that has gone. */
    @Test
    fun `nothing is exported or excluded that the settings no longer have`() {
        val handled = SettingsTransfer.EXPORTED_FIELDS + SettingsTransfer.EXCLUDED_FIELDS.keys
        val stale = handled - protoFields()
        assertEquals(emptySet(), stale, "The settings export names field(s) AppSettings does not have: $stale")
    }

    /**
     * The message's fields, as protobuf spells its accessors.
     *
     * `declaredMethods` and not `methods`, so nothing inherited from `GeneratedMessageLite` is counted.
     * The suffixes below are protobuf's own generated conveniences around a single field — a repeated
     * field gets `…List` and `…Count`, a map gets `…Map`, `…OrDefault` and `…OrThrow`, a string gets
     * `…Bytes`, an enum gets `…Value` — and every one of them names a field the plain accessor already
     * names.
     */
    private fun protoFields(): Set<String> = AppSettings::class.java.declaredMethods
        .map(Method::getName)
        .filter { it.startsWith("get") && it.length > "get".length && it["get".length].isUpperCase() }
        .map { it.removePrefix("get") }
        .filterNot { name -> SYNTHETIC_SUFFIXES.any { name.endsWith(it) } }
        .filterNot { it in NOT_A_FIELD }
        .toSet()

    private companion object {
        val SYNTHETIC_SUFFIXES = listOf("Bytes", "Count", "List", "Map", "OrDefault", "OrThrow", "Value")

        /** Message plumbing rather than settings. */
        val NOT_A_FIELD = setOf("DefaultInstance", "DefaultInstanceForType", "ParserForType", "SerializedSize")
    }
}
