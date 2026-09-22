package ai.openclaw.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ProtocolException

class GatewayUpgradeDiagnosticsTest {
  private val redirect = ProtocolException("Expected HTTP 101 response but was '302 Found'")

  @Test
  fun aRedirectedUpgradeWithoutHeadersPointsAtTheEdgeInsteadOfTheGateway() {
    val message = gatewayTransportFailureMessage(redirect, responseCode = 302, sentCustomHeaders = false)

    assertTrue(message.contains("HTTP 302"))
    assertTrue(message.contains("Cloudflare Access"))
    assertTrue(message.contains("Advanced connection headers"))
  }

  @Test
  fun aRedirectDespiteConfiguredHeadersBlamesTheHeadersRatherThanAskingForThemAgain() {
    val message = gatewayTransportFailureMessage(redirect, responseCode = 302, sentCustomHeaders = true)

    assertTrue(message.contains("even though"))
    assertFalse(message.contains("Cloudflare Access"))
  }

  @Test
  fun rejectionCodesSeparateAMissingCredentialFromAWrongOne() {
    val missing = gatewayTransportFailureMessage(redirect, responseCode = 403, sentCustomHeaders = false)
    val wrong = gatewayTransportFailureMessage(redirect, responseCode = 403, sentCustomHeaders = true)

    assertTrue(missing.contains("requires authentication"))
    assertTrue(wrong.contains("rejected the connection headers"))
    assertTrue(wrong.contains("expired"))
  }

  @Test
  fun aFailureWithNoHttpResponseKeepsTheOriginalTransportWording() {
    val error = java.net.SocketTimeoutException("timeout")

    assertEquals(
      "Gateway error: timeout",
      gatewayTransportFailureMessage(error, responseCode = null, sentCustomHeaders = true),
    )
  }

  @Test
  fun anUnrelatedServerErrorIsNotRewrittenIntoEdgeAdvice() {
    val error = ProtocolException("Expected HTTP 101 response but was '502 Bad Gateway'")
    val message = gatewayTransportFailureMessage(error, responseCode = 502, sentCustomHeaders = false)

    assertEquals("Gateway error: Expected HTTP 101 response but was '502 Bad Gateway'", message)
  }

  @Test
  fun aThrowableWithoutAMessageStillNamesSomething() {
    val message = gatewayTransportFailureMessage(ProtocolException(), responseCode = null, sentCustomHeaders = false)

    assertEquals("Gateway error: ProtocolException", message)
  }
}
