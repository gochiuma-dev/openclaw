package ai.openclaw.app.gateway

/**
 * Turns a failed gateway transport into operator-actionable copy.
 *
 * Identity-aware proxies (Cloudflare Access and friends) answer an unauthenticated WebSocket
 * upgrade with a redirect to their login page instead of HTTP 101. OkHttp reports that only as
 * "Expected HTTP 101 response but was ...", which reads like a broken gateway rather than a
 * missing edge credential, so the redirect and rejection codes get their own wording.
 *
 * [sentCustomHeaders] says whether the upgrade carried operator headers; header values never
 * reach this function, so no returned string can leak a service token.
 */
internal fun gatewayTransportFailureMessage(
  error: Throwable,
  responseCode: Int?,
  sentCustomHeaders: Boolean,
): String {
  val detail = error.message ?: error::class.java.simpleName
  return when {
    responseCode == null -> {
      "Gateway error: $detail"
    }

    responseCode in 300..399 && sentCustomHeaders -> {
      "Gateway error: the edge redirected the connection (HTTP $responseCode) even though " +
        "connection headers were sent. Check the header names and values under Advanced " +
        "connection headers."
    }

    responseCode in 300..399 -> {
      "Gateway error: the edge redirected the connection (HTTP $responseCode). An identity-aware " +
        "proxy such as Cloudflare Access is probably in front of this gateway. Add its " +
        "service-token headers under Advanced connection headers."
    }

    responseCode == 401 || responseCode == 403 -> {
      if (sentCustomHeaders) {
        "Gateway error: the edge rejected the connection headers (HTTP $responseCode). The " +
          "service token looks wrong, expired, or is not allowed by the edge policy."
      } else {
        "Gateway error: the edge requires authentication (HTTP $responseCode). Add the proxy's " +
          "service-token headers under Advanced connection headers."
      }
    }

    responseCode == 407 -> {
      "Gateway error: an HTTP proxy on this network requires authentication (HTTP 407)."
    }

    else -> {
      "Gateway error: $detail"
    }
  }
}
