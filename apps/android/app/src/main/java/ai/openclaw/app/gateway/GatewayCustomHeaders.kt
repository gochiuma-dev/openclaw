package ai.openclaw.app.gateway

/**
 * One operator edit to a stored header. A blank [value] keeps whatever is already stored, so the
 * editor never has to read a credential back out of the encrypted store just to re-save it.
 */
data class GatewayCustomHeaderDraft(
  val name: String,
  val value: String = "",
  val removed: Boolean = false,
) {
  /**
   * A data class prints every field, and this one travels through UI state that can end up in an
   * exception message or a crash report. Report whether a value is present, never the value.
   */
  override fun toString(): String {
    val shownValue = if (value.isEmpty()) "unset" else "redacted"
    return "GatewayCustomHeaderDraft(name=$name, value=$shownValue, removed=$removed)"
  }
}

/**
 * Operator-defined HTTP headers attached to gateway connections so gateways fronted by
 * authenticating reverse proxies (Cloudflare Access-style service tokens) stay reachable.
 * Header values are credentials: persist them only in SecurePrefs and never log them.
 */
object GatewayCustomHeaders {
  // Connection-management headers the WebSocket upgrade owns. Operator overrides here would
  // corrupt the handshake or duplicate fields OkHttp sets itself.
  private val reservedNames =
    setOf("connection", "content-length", "host", "proxy-connection", "upgrade")
  private const val RESERVED_PREFIX = "sec-websocket-"
  private const val TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~"

  fun isReservedName(name: String): Boolean {
    val normalized = name.trim().lowercase()
    return normalized in reservedNames || normalized.startsWith(RESERVED_PREFIX)
  }

  /**
   * Drops entries that cannot travel as a single well-formed header: empty, reserved, or
   * non-token names, and values outside printable ASCII. Dropping invalid entries keeps one bad
   * stored value from wedging every reconnect or being interpreted differently by a proxy.
   */
  fun sanitized(headers: Map<String, String>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for ((rawName, value) in headers) {
      val name = rawName.trim()
      if (name.isEmpty() || isReservedName(name)) continue
      if (!name.all(::isTokenCharacter)) continue
      if (!value.all { it in ' '..'~' }) continue
      result[name] = value
    }
    return result
  }

  /**
   * Applies operator edits over the headers already stored for one gateway. Names match
   * case-insensitively because HTTP field names are case-insensitive and a stored
   * `CF-Access-Client-Id` must not end up beside a retyped `cf-access-client-id`.
   */
  fun merged(
    stored: Map<String, String>,
    drafts: List<GatewayCustomHeaderDraft>,
  ): Map<String, String> {
    val result = LinkedHashMap(stored)
    for (draft in drafts) {
      val name = draft.name.trim()
      if (name.isEmpty()) continue
      val storedName = result.keys.firstOrNull { it.equals(name, ignoreCase = true) }
      if (draft.removed) {
        storedName?.let(result::remove)
        continue
      }
      val value = draft.value.trim()
      // A name typed without a value leaves the stored secret alone; it never clears one.
      if (value.isEmpty()) continue
      if (storedName != null && storedName != name) result.remove(storedName)
      result[name] = value
    }
    return sanitized(result)
  }

  private fun isTokenCharacter(character: Char): Boolean =
    character in '0'..'9' ||
      character in 'A'..'Z' ||
      character in 'a'..'z' ||
      character in TOKEN_PUNCTUATION
}
