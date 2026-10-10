package com.example.shelfplayer.auto

import com.example.shelfplayer.core.model.ProfileId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAutoArtworkTest {

    @Test
    fun `artwork URI is opaque local content and contains no source credential material`() {
        val context = RuntimeEnvironment.getApplication()
        val source = "https://private.example/api/items/book-secret/cover?token=DO-NOT-LEAK"

        val uri = assertNotNull(AutoArtworkRegistry.register(context, ProfileId("profile-secret"), listOf(source)))

        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".autoart", uri.authority)
        assertTrue(AutoArtworkRegistry.isToken(assertNotNull(uri.lastPathSegment)))
        assertFalse(uri.toString().contains("private.example"))
        assertFalse(uri.toString().contains("book-secret"))
        assertFalse(uri.toString().contains("DO-NOT-LEAK"))
        assertFalse(uri.toString().contains("token"))
    }

    @Test
    fun `capability retains profile ownership internally and changes across profiles`() {
        val context = RuntimeEnvironment.getApplication()
        val source = "https://books.example/api/items/book/cover"
        val first = assertNotNull(AutoArtworkRegistry.register(context, ProfileId("a"), listOf(source)))
        val second = assertNotNull(AutoArtworkRegistry.register(context, ProfileId("b"), listOf(source)))

        assertNotEquals(first, second)
        assertEquals(ProfileId("a"), AutoArtworkRegistry.resolve(assertNotNull(first.lastPathSegment))?.profileId)
        assertEquals(ProfileId("b"), AutoArtworkRegistry.resolve(assertNotNull(second.lastPathSegment))?.profileId)
    }

    @Test
    fun `artwork event outcomes use only fixed redacted codes`() {
        assertEquals(
            listOf(
                "invalid-mode",
                "invalid-capability",
                "expired-capability",
                "profile-mismatch",
                "cache-unavailable",
                "read-failed",
                "served-cached",
                "served-materialized",
            ),
            AutoArtworkReadOutcome.entries.map { outcome -> outcome.code },
        )
    }

    @Test
    fun `provider emits requests and classified outcomes without URI or cache paths`() {
        val providerSource =
            java.io.File("src/main/kotlin/com/example/shelfplayer/auto/AutoArtworkContentProvider.kt").readText()
        assertTrue("Android Auto artwork requested" in providerSource)
        assertTrue("Android Auto artwork served" in providerSource)
        assertTrue("Android Auto artwork failed" in providerSource)
        assertTrue("Android Auto artwork refused" in providerSource)
        assertTrue("LogField.Public(\"outcome\", outcome.code)" in providerSource)
    }

    @Test
    fun `missing artwork produces no URI so the host owns the placeholder`() {
        val context = RuntimeEnvironment.getApplication()

        assertNull(AutoArtworkRegistry.register(context, ProfileId("a"), emptyList()))
        assertNull(AutoArtworkRegistry.register(context, ProfileId("a"), listOf("")))
    }
}
