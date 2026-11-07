package ai.openclaw.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCustomHeadersMergeTest {
  @Test
  fun blankValueKeepsTheStoredSecretSoTheEditorNeverHasToReadItBack() {
    val merged =
      GatewayCustomHeaders.merged(
        stored = mapOf("CF-Access-Client-Id" to "stored-id", "CF-Access-Client-Secret" to "stored-secret"),
        drafts = listOf(GatewayCustomHeaderDraft("CF-Access-Client-Secret", "rotated-secret")),
      )

    assertEquals(
      mapOf("CF-Access-Client-Id" to "stored-id", "CF-Access-Client-Secret" to "rotated-secret"),
      merged,
    )
  }

  @Test
  fun aNameTypedWithoutAValueNeverClearsOrInventsAHeader() {
    val merged =
      GatewayCustomHeaders.merged(
        stored = mapOf("X-Edge-Secret" to "stored"),
        drafts =
          listOf(
            GatewayCustomHeaderDraft("X-Edge-Secret", "   "),
            GatewayCustomHeaderDraft("X-Never-Set", ""),
          ),
      )

    assertEquals(mapOf("X-Edge-Secret" to "stored"), merged)
  }

  @Test
  fun removalDropsTheHeaderAndMatchesTheStoredNameCaseInsensitively() {
    val merged =
      GatewayCustomHeaders.merged(
        stored = mapOf("CF-Access-Client-Id" to "stored-id", "X-Keep" to "keep"),
        drafts = listOf(GatewayCustomHeaderDraft("cf-access-client-id", removed = true)),
      )

    assertEquals(mapOf("X-Keep" to "keep"), merged)
  }

  @Test
  fun retypingANameInAnotherCaseReplacesTheStoredEntryInsteadOfDuplicatingIt() {
    val merged =
      GatewayCustomHeaders.merged(
        stored = mapOf("cf-access-client-id" to "stored-id"),
        drafts = listOf(GatewayCustomHeaderDraft("CF-Access-Client-Id", "typed-id")),
      )

    assertEquals(mapOf("CF-Access-Client-Id" to "typed-id"), merged)
  }

  @Test
  fun reservedAndMalformedNamesAreDroppedRatherThanStoredForEveryFutureReconnect() {
    val merged =
      GatewayCustomHeaders.merged(
        stored = emptyMap(),
        drafts =
          listOf(
            GatewayCustomHeaderDraft("Host", "smuggled.example"),
            GatewayCustomHeaderDraft("Sec-WebSocket-Key", "forged"),
            GatewayCustomHeaderDraft("Bad Name", "value"),
            GatewayCustomHeaderDraft("X-Edge-Secret", "kept"),
          ),
      )

    assertEquals(mapOf("X-Edge-Secret" to "kept"), merged)
  }

  @Test
  fun trimmingKeepsAPastedTokenUsableWhenItArrivesWithSurroundingWhitespace() {
    val merged =
      GatewayCustomHeaders.merged(
        stored = emptyMap(),
        drafts = listOf(GatewayCustomHeaderDraft("  CF-Access-Client-Id  ", "  pasted-id\n")),
      )

    assertEquals(mapOf("CF-Access-Client-Id" to "pasted-id"), merged)
  }

  @Test
  fun aDraftNeverPrintsItsValueSoCrashReportsCannotCarryAServiceToken() {
    val rendered = GatewayCustomHeaderDraft("CF-Access-Client-Secret", "super-secret").toString()

    assertFalse(rendered.contains("super-secret"))
    assertTrue(rendered.contains("CF-Access-Client-Secret"))
    assertTrue(GatewayCustomHeaderDraft("X-Empty").toString().contains("unset"))
  }

  @Test
  fun noDraftsLeavesTheStoredHeadersExactlyAsTheyWere() {
    val stored = mapOf("CF-Access-Client-Id" to "id", "CF-Access-Client-Secret" to "secret")

    assertEquals(stored, GatewayCustomHeaders.merged(stored, emptyList()))
    assertTrue(GatewayCustomHeaders.merged(emptyMap(), emptyList()).isEmpty())
  }
}
