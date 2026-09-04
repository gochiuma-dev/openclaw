package ai.openclaw.app.ui

import ai.openclaw.app.gateway.GatewayCustomHeaderDraft
import ai.openclaw.app.gateway.GatewayCustomHeaders
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Cloudflare Access service-token header names. The shortcut in the editor writes exactly these
 * two so operators never have to retype them, but they are ordinary custom headers underneath —
 * any other identity-aware proxy is configured through the generic rows instead.
 */
internal const val CLOUDFLARE_ACCESS_CLIENT_ID_HEADER = "CF-Access-Client-Id"
internal const val CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER = "CF-Access-Client-Secret"

/**
 * One generic header row. [stored] records that a value already exists for this name without
 * carrying the value: a blank [value] therefore means "keep the stored secret", and only
 * [removed] clears one.
 */
internal data class GatewayHeaderRow(
  val key: Long,
  val name: String,
  val value: String = "",
  val stored: Boolean = false,
  val removed: Boolean = false,
) {
  /** Redacted for the same reason as [GatewayCustomHeaderDraft]: a data class prints its fields. */
  override fun toString(): String {
    val shownValue = if (value.isEmpty()) "unset" else "redacted"
    return "GatewayHeaderRow(key=$key, name=$name, value=$shownValue, stored=$stored, removed=$removed)"
  }
}

/**
 * Editor state for one gateway's connection headers.
 *
 * Stored values are deliberately never loaded into it. The editor is told only which header
 * names exist, so a saved service token cannot be read back out of the encrypted store through
 * Compose, and no secret can reach a screenshot, a crash dump, or a saved instance state.
 */
internal data class GatewayHeaderEditorState(
  val cloudflareClientId: String = "",
  val cloudflareClientSecret: String = "",
  val cloudflareStored: Boolean = false,
  val cloudflareRemoved: Boolean = false,
  val rows: List<GatewayHeaderRow> = emptyList(),
  val nextRowKey: Long = 0,
) {
  /** Redacted: this is live UI state, and a data class prints every field it holds. */
  override fun toString(): String {
    val shownId = if (cloudflareClientId.isEmpty()) "unset" else "redacted"
    val shownSecret = if (cloudflareClientSecret.isEmpty()) "unset" else "redacted"
    return "GatewayHeaderEditorState(cloudflareClientId=$shownId, cloudflareClientSecret=$shownSecret, " +
      "cloudflareStored=$cloudflareStored, cloudflareRemoved=$cloudflareRemoved, rows=$rows)"
  }
}

/** Seeds the editor from the header names already stored for a gateway. */
internal fun gatewayHeaderEditorState(storedNames: List<String>): GatewayHeaderEditorState {
  val cloudflareNames =
    setOf(
      CLOUDFLARE_ACCESS_CLIENT_ID_HEADER.lowercase(),
      CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER.lowercase(),
    )
  val generic = storedNames.filterNot { it.trim().lowercase() in cloudflareNames }
  return GatewayHeaderEditorState(
    cloudflareStored = storedNames.any { it.trim().lowercase() in cloudflareNames },
    rows = generic.mapIndexed { index, name -> GatewayHeaderRow(key = index.toLong(), name = name, stored = true) },
    nextRowKey = generic.size.toLong(),
  )
}

/** Whether the operator changed anything the store still has to be told about. */
internal fun GatewayHeaderEditorState.hasEdits(): Boolean = drafts().isNotEmpty()

/** Projects the editor onto the store's edit model. Blank values keep the stored secret. */
internal fun GatewayHeaderEditorState.drafts(): List<GatewayCustomHeaderDraft> {
  val drafts = mutableListOf<GatewayCustomHeaderDraft>()
  if (cloudflareRemoved) {
    drafts += GatewayCustomHeaderDraft(CLOUDFLARE_ACCESS_CLIENT_ID_HEADER, removed = true)
    drafts += GatewayCustomHeaderDraft(CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER, removed = true)
  } else {
    if (cloudflareClientId.isNotBlank()) {
      drafts += GatewayCustomHeaderDraft(CLOUDFLARE_ACCESS_CLIENT_ID_HEADER, cloudflareClientId)
    }
    if (cloudflareClientSecret.isNotBlank()) {
      drafts += GatewayCustomHeaderDraft(CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER, cloudflareClientSecret)
    }
  }
  for (row in rows) {
    if (row.name.isBlank()) continue
    if (row.removed) {
      // A row the operator added and then deleted was never stored; nothing to tell the store.
      if (row.stored) drafts += GatewayCustomHeaderDraft(row.name, removed = true)
      continue
    }
    if (row.value.isBlank()) continue
    drafts += GatewayCustomHeaderDraft(row.name, row.value)
  }
  return drafts
}

/**
 * State to keep after a save: typed secrets are dropped and the rows now describe what the store
 * holds, so the form stops offering to re-send a credential it no longer has in memory.
 */
internal fun GatewayHeaderEditorState.committed(): GatewayHeaderEditorState =
  copy(
    cloudflareClientId = "",
    cloudflareClientSecret = "",
    cloudflareStored =
      !cloudflareRemoved &&
        (cloudflareStored || cloudflareClientId.isNotBlank() || cloudflareClientSecret.isNotBlank()),
    cloudflareRemoved = false,
    rows =
      rows
        .asSequence()
        .filterNot { it.removed }
        .map { it.copy(value = "", stored = it.stored || it.value.isNotBlank()) }
        .filter { it.stored && it.name.isNotBlank() }
        .toList(),
  )

/**
 * Reports a header name the operator typed that the store would silently drop, so a mistyped
 * name surfaces in the form instead of vanishing between save and reconnect.
 */
internal fun GatewayHeaderEditorState.rejectedHeaderName(): String? =
  rows
    .asSequence()
    .filterNot { it.removed }
    .map { it.name.trim() }
    .filter { it.isNotEmpty() }
    .firstOrNull { name -> GatewayCustomHeaders.sanitized(mapOf(name to "probe")).isEmpty() }

/**
 * Advanced controls for the headers a gateway connection carries through an authenticating
 * reverse proxy. Values are entered masked and are never rendered back from the store.
 */
@Composable
internal fun GatewayAdvancedHeadersSection(
  state: GatewayHeaderEditorState,
  onStateChange: (GatewayHeaderEditorState) -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasStored = state.cloudflareStored || state.rows.any { it.stored }
  var expanded by remember(hasStored) { mutableStateOf(hasStored) }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = nativeString("Advanced connection headers"),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) nativeString("Hide") else nativeString("Show"))
      }
    }
    if (!expanded) {
      Text(
        text =
          if (hasStored) {
            nativeString("Headers are configured for this gateway.")
          } else {
            nativeString("Needed only when an identity-aware proxy fronts this gateway.")
          },
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
      )
      return@Column
    }

    Text(
      text =
        nativeString(
          "Sent with every request to this gateway over TLS. Use them for Cloudflare Access " +
            "service tokens or another proxy's shared secret; they do not replace the gateway " +
            "token or device approval.",
        ),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textMuted,
    )

    CloudflareAccessShortcut(state = state, onStateChange = onStateChange)

    HorizontalDivider(color = ClawTheme.colors.border)

    Text(
      text = nativeString("Custom headers"),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textMuted,
    )
    state.rows.forEachIndexed { index, row ->
      if (row.removed) return@forEachIndexed
      GatewayHeaderRowFields(
        row = row,
        onRowChange = { updated ->
          onStateChange(state.copy(rows = state.rows.toMutableList().also { it[index] = updated }))
        },
      )
    }
    ClawSecondaryButton(
      text = nativeString("Add header"),
      icon = Icons.Default.Add,
      onClick = {
        onStateChange(
          state.copy(
            rows = state.rows + GatewayHeaderRow(key = state.nextRowKey, name = ""),
            nextRowKey = state.nextRowKey + 1,
          ),
        )
      },
      modifier = Modifier.fillMaxWidth(),
    )
    state.rejectedHeaderName()?.let { name ->
      Text(
        text = nativeString("\$name is not a header name this gateway can send; it will be skipped.", name),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.warning,
      )
    }
  }
}

@Composable
private fun CloudflareAccessShortcut(
  state: GatewayHeaderEditorState,
  onStateChange: (GatewayHeaderEditorState) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = nativeString("Cloudflare Access service token"),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textMuted,
    )
    ClawTextField(
      value = state.cloudflareClientId,
      onValueChange = { onStateChange(state.copy(cloudflareClientId = it, cloudflareRemoved = false)) },
      placeholder = cloudflareFieldPlaceholder(state, nativeString("Client Id")),
      label = nativeString("Cloudflare Access Client Id"),
      // The client id identifies the token, not the secret half; masking it would only make
      // typos undetectable. The secret below is the credential.
    )
    ClawTextField(
      value = state.cloudflareClientSecret,
      onValueChange = { onStateChange(state.copy(cloudflareClientSecret = it, cloudflareRemoved = false)) },
      placeholder = cloudflareFieldPlaceholder(state, nativeString("Client Secret")),
      label = nativeString("Cloudflare Access Client Secret"),
      secret = true,
    )
    Text(
      text =
        nativeString(
          "Expands to the \$id and \$secret headers.",
          CLOUDFLARE_ACCESS_CLIENT_ID_HEADER,
          CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER,
        ),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textSubtle,
    )
    if (state.cloudflareStored && !state.cloudflareRemoved) {
      TextButton(
        onClick = {
          onStateChange(
            state.copy(cloudflareClientId = "", cloudflareClientSecret = "", cloudflareRemoved = true),
          )
        },
      ) {
        Text(nativeString("Remove Cloudflare Access headers"))
      }
    }
    if (state.cloudflareRemoved) {
      Text(
        text = nativeString("Cloudflare Access headers are removed on save."),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.warning,
      )
    }
  }
}

@Composable
private fun GatewayHeaderRowFields(
  row: GatewayHeaderRow,
  onRowChange: (GatewayHeaderRow) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    ClawTextField(
      value = row.name,
      onValueChange = { onRowChange(row.copy(name = it)) },
      placeholder = nativeString("Header name"),
      label = nativeString("Header name"),
      // A stored row's name is its identity in the store; renaming it would orphan the secret.
      enabled = !row.stored,
    )
    ClawTextField(
      value = row.value,
      onValueChange = { onRowChange(row.copy(value = it)) },
      placeholder =
        if (row.stored) nativeString("Saved — type to replace") else nativeString("Header value"),
      label = nativeString("Header value"),
      secret = true,
    )
    TextButton(onClick = { onRowChange(row.copy(removed = true, value = "")) }) {
      Text(nativeString("Remove"))
    }
  }
}

private fun cloudflareFieldPlaceholder(
  state: GatewayHeaderEditorState,
  label: String,
): String =
  if (state.cloudflareStored && !state.cloudflareRemoved) {
    nativeString("Saved — type to replace")
  } else {
    label
  }
