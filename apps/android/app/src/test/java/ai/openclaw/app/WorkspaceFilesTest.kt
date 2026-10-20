package ai.openclaw.app

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.ui.WorkspacePreviewFailure
import ai.openclaw.app.ui.initialWorkspaceAgentId
import ai.openclaw.app.ui.workspaceAgentOptions
import ai.openclaw.app.ui.workspacePreviewFailure
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFilesTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun filesOpenOnTheOpenConversationsAgent() {
    // A conversation opened from the sidebar keeps the main session on main; its own key names the agent.
    assertEquals(
      "test",
      initialWorkspaceAgentId(
        chatSessionKey = "agent:test:dashboard:abc",
        chatOwnerAgentId = "test",
        mainSessionKey = "agent:main:node-1234",
        gatewayDefaultAgentId = "main",
      ),
    )
    // A legacy alias key carries no agent; the owner the sidebar passed decides.
    assertEquals("test", initialWorkspaceAgentId("main", " test ", "agent:main:node-1234", "main"))
    // Neither names an agent: fall back to the runtime's active agent, then the gateway default.
    assertEquals("ops", initialWorkspaceAgentId("main", null, "agent:ops:node-1234", "main"))
    assertEquals("main", initialWorkspaceAgentId("global", "", "main", "main"))
    assertEquals("main", initialWorkspaceAgentId(null, null, null, null))
  }

  @Test
  fun pickerKeepsAnAgentTheGatewayHasNotListedYet() {
    val main = GatewayAgentSummary("main", "雨衣", "☂️")
    val test = GatewayAgentSummary("test", "雨衣（試）", null)
    assertEquals(listOf(main, test), workspaceAgentOptions(listOf(main, test), "test"))
    assertEquals(listOf(main, GatewayAgentSummary("test", null, null)), workspaceAgentOptions(listOf(main), "test"))
    assertEquals(listOf(GatewayAgentSummary("main", null, null)), workspaceAgentOptions(emptyList(), "main"))
  }

  @Test
  fun previewFailuresNameTheirCause() {
    fun rejected(message: String) = GatewayRequestRejected(GatewaySession.ErrorShape("INVALID_REQUEST", message))
    // Messages from agents.workspace.get on the gateway.
    assertEquals(WorkspacePreviewFailure.TooLarge, workspacePreviewFailure(rejected("workspace file is too large to preview")))
    assertEquals(
      WorkspacePreviewFailure.Unsupported,
      workspacePreviewFailure(rejected("workspace file is not UTF-8 text or a supported image")),
    )
    assertEquals(WorkspacePreviewFailure.Other, workspacePreviewFailure(rejected("workspace file not found")))
    assertEquals(WorkspacePreviewFailure.Timeout, workspacePreviewFailure(GatewayRequestOutcomeUnknown("request timeout")))
    assertEquals(WorkspacePreviewFailure.Network, workspacePreviewFailure(GatewayRequestOutcomeUnknown("Gateway disconnected before response")))
    assertEquals(WorkspacePreviewFailure.Network, workspacePreviewFailure(GatewayRequestNotEnqueued("not connected")))
    assertEquals(WorkspacePreviewFailure.Network, workspacePreviewFailure(java.io.IOException("socket closed")))
    assertEquals(WorkspacePreviewFailure.Other, workspacePreviewFailure(IllegalStateException("agents.workspace.get returned no file")))
  }

  @Test
  fun parsesListingEntriesAndPagination() {
    val payload =
      """
      {
        "agentId": "main",
        "workspace": "/tmp/workspace",
        "path": "src",
        "parentPath": "",
        "entries": [
          {"path": "src/util", "name": "util", "kind": "directory", "updatedAtMs": 1700000000000},
          {"path": "src/index.ts", "name": "index.ts", "kind": "file", "size": 42, "updatedAtMs": 1700000000123}
        ],
        "totalEntries": 12,
        "offset": 0
      }
      """.trimIndent()

    val listing = parseWorkspaceListing(json.parseToJsonElement(payload))

    assertEquals("src", listing?.path)
    assertEquals(12, listing?.totalEntries)
    assertEquals(0, listing?.offset)
    assertEquals(2, listing?.entries?.size)
    val directory = listing?.entries?.first()
    assertEquals(true, directory?.isDirectory)
    assertNull(directory?.size)
    val file = listing?.entries?.last()
    assertEquals(false, file?.isDirectory)
    assertEquals(42L, file?.size)
    assertEquals(1_700_000_000_123L, file?.updatedAtMs)
  }

  @Test
  fun parsesTextAndImageFilePayloads() {
    val text =
      parseWorkspaceFile(
        json.parseToJsonElement(
          """{"agentId":"main","workspace":"/w","file":{"path":"notes.md","name":"notes.md","size":8,"updatedAtMs":1,"mimeType":"text/plain","encoding":"utf8","content":"# Notes\n"}}""",
        ),
      )
    assertEquals("notes.md", text?.name)
    assertEquals(false, text?.isBase64)
    assertEquals("# Notes\n", text?.content)

    val image =
      parseWorkspaceFile(
        json.parseToJsonElement(
          """{"agentId":"main","workspace":"/w","file":{"path":"shot.png","name":"shot.png","size":3,"updatedAtMs":1,"mimeType":"image/png","encoding":"base64","content":"AAECAw=="}}""",
        ),
      )
    assertEquals(true, image?.isBase64)
    assertTrue(image?.mimeType?.startsWith("image/") == true)
  }

  @Test
  fun rejectsPayloadsWithoutFileOrPath() {
    assertNull(parseWorkspaceFile(Json.parseToJsonElement("""{"agentId":"main"}""")))
    assertNull(
      parseWorkspaceFile(
        Json.parseToJsonElement("""{"file":{"name":"x","size":1,"mimeType":"text/plain","encoding":"utf8","content":""}}"""),
      ),
    )
  }
}
