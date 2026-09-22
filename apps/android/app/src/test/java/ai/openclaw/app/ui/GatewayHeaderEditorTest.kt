package ai.openclaw.app.ui

import ai.openclaw.app.gateway.GatewayCustomHeaderDraft
import ai.openclaw.app.gateway.GatewayCustomHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayHeaderEditorTest {
  @Test
  fun theCloudflareShortcutExpandsToTheTwoServiceTokenHeaders() {
    val state =
      GatewayHeaderEditorState(
        cloudflareClientId = "client-id",
        cloudflareClientSecret = "client-secret",
      )

    assertEquals(
      mapOf(
        CLOUDFLARE_ACCESS_CLIENT_ID_HEADER to "client-id",
        CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER to "client-secret",
      ),
      GatewayCustomHeaders.merged(emptyMap(), state.drafts()),
    )
  }

  @Test
  fun seedingFromStoredNamesCarriesNoValuesAndSplitsCloudflareFromGenericRows() {
    val state =
      gatewayHeaderEditorState(
        listOf("cf-access-client-id", "CF-Access-Client-Secret", "X-Edge-Secret"),
      )

    assertTrue(state.cloudflareStored)
    assertEquals("", state.cloudflareClientSecret)
    assertEquals(listOf("X-Edge-Secret"), state.rows.map { it.name })
    assertTrue(state.rows.all { it.stored && it.value.isEmpty() })
    // Nothing to tell the store until the operator actually types something.
    assertFalse(state.hasEdits())
  }

  @Test
  fun rotatingOnlyTheSecretLeavesTheStoredClientIdInPlace() {
    val stored =
      mapOf(
        CLOUDFLARE_ACCESS_CLIENT_ID_HEADER to "stored-id",
        CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER to "stored-secret",
      )
    val state =
      gatewayHeaderEditorState(stored.keys.toList()).copy(cloudflareClientSecret = "rotated")

    assertEquals(
      mapOf(
        CLOUDFLARE_ACCESS_CLIENT_ID_HEADER to "stored-id",
        CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER to "rotated",
      ),
      GatewayCustomHeaders.merged(stored, state.drafts()),
    )
  }

  @Test
  fun removingCloudflareAccessClearsBothHeadersAndOutlivesAStaleTypedValue() {
    val stored =
      mapOf(
        CLOUDFLARE_ACCESS_CLIENT_ID_HEADER to "stored-id",
        CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER to "stored-secret",
        "X-Edge-Secret" to "keep",
      )
    val state =
      gatewayHeaderEditorState(stored.keys.toList())
        .copy(cloudflareClientId = "typed-but-discarded", cloudflareRemoved = true)

    assertEquals(mapOf("X-Edge-Secret" to "keep"), GatewayCustomHeaders.merged(stored, state.drafts()))
  }

  @Test
  fun deletingARowThatWasNeverStoredTellsTheStoreNothing() {
    val state =
      GatewayHeaderEditorState(
        rows = listOf(GatewayHeaderRow(key = 0, name = "X-Typo", value = "oops", removed = true)),
      )

    assertEquals(emptyList<GatewayCustomHeaderDraft>(), state.drafts())
  }

  @Test
  fun deletingAStoredRowRemovesItWithoutTouchingItsNeighbours() {
    val stored = mapOf("X-Edge-Secret" to "secret", "X-Other" to "other")
    val state =
      gatewayHeaderEditorState(stored.keys.toList())
        .let { seeded -> seeded.copy(rows = seeded.rows.map { if (it.name == "X-Other") it.copy(removed = true) else it }) }

    assertEquals(mapOf("X-Edge-Secret" to "secret"), GatewayCustomHeaders.merged(stored, state.drafts()))
  }

  @Test
  fun committingASaveDropsTypedSecretsButRemembersWhichNamesNowExist() {
    val state =
      GatewayHeaderEditorState(
        cloudflareClientId = "client-id",
        cloudflareClientSecret = "client-secret",
        rows =
          listOf(
            GatewayHeaderRow(key = 0, name = "X-Edge-Secret", value = "secret"),
            GatewayHeaderRow(key = 1, name = "X-Gone", stored = true, removed = true),
            GatewayHeaderRow(key = 2, name = "", value = ""),
          ),
      ).committed()

    assertEquals("", state.cloudflareClientId)
    assertEquals("", state.cloudflareClientSecret)
    assertTrue(state.cloudflareStored)
    assertFalse(state.cloudflareRemoved)
    assertEquals(listOf("X-Edge-Secret"), state.rows.map { it.name })
    assertTrue(state.rows.single().stored)
    assertEquals("", state.rows.single().value)
    assertFalse(state.hasEdits())
  }

  @Test
  fun committingARemovalStopsReportingCloudflareAccessAsConfigured() {
    val state = gatewayHeaderEditorState(listOf(CLOUDFLARE_ACCESS_CLIENT_ID_HEADER)).copy(cloudflareRemoved = true)

    assertFalse(state.committed().cloudflareStored)
  }

  @Test
  fun editorStateNeverPrintsATypedSecret() {
    val rendered =
      GatewayHeaderEditorState(
        cloudflareClientId = "id-value",
        cloudflareClientSecret = "secret-value",
        rows = listOf(GatewayHeaderRow(key = 0, name = "X-Edge-Secret", value = "row-value")),
      ).toString()

    assertFalse(rendered.contains("id-value"))
    assertFalse(rendered.contains("secret-value"))
    assertFalse(rendered.contains("row-value"))
    assertTrue(rendered.contains("X-Edge-Secret"))
  }

  @Test
  fun aHeaderNameTheStoreWouldDropIsReportedBackToTheForm() {
    val rejected =
      GatewayHeaderEditorState(rows = listOf(GatewayHeaderRow(key = 0, name = "Host", value = "smuggled.example")))
    val accepted =
      GatewayHeaderEditorState(rows = listOf(GatewayHeaderRow(key = 0, name = "X-Edge-Secret", value = "ok")))

    assertEquals("Host", rejected.rejectedHeaderName())
    assertNull(accepted.rejectedHeaderName())
  }

  @Test
  fun aRowDeletedAfterBeingTypedWrongNoLongerWarns() {
    val state =
      GatewayHeaderEditorState(
        rows = listOf(GatewayHeaderRow(key = 0, name = "Host", value = "x", removed = true)),
      )

    assertNull(state.rejectedHeaderName())
  }
}
