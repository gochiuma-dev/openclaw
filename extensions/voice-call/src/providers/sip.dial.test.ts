import { describe, expect, it, vi, afterEach } from "vitest";
import { SipProvider } from "./sip.js";

/**
 * An extension and an outside number leave on different channel technologies.
 * Sending an extension to the trunk endpoint would place a real outside call, so the
 * split is worth pinning down.
 */
function createProvider(overrides?: { extensionPattern?: string }) {
  return new SipProvider({
    bind: "127.0.0.1",
    port: 0,
    relayUrl: "http://relay.invalid",
    relayUser: "",
    relayPassword: "",
    silenceMs: 800,
    silenceRms: 500,
    minSpeechMs: 200,
    maxUtteranceMs: 20_000,
    ari: {
      baseUrl: "http://pbx.invalid/ari",
      username: "u",
      password: "p",
      endpoint: "Quectel/quectel0/{number}",
      extensionEndpoint: "PJSIP/{number}",
      extensionPattern: overrides?.extensionPattern ?? "^\\d{3,4}$",
      context: "openclaw-outbound",
      extension: "701",
      timeoutSeconds: 45,
    },
    onEvent: () => {},
  });
}

function stubOriginate() {
  const calls: Array<Record<string, unknown>> = [];
  const fetchMock = vi.fn(async (_url: string, init?: { body?: string }) => {
    calls.push(JSON.parse(String(init?.body ?? "{}")));
    return { ok: true, status: 200, text: async () => "" } as unknown as Response;
  });
  vi.stubGlobal("fetch", fetchMock);
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("SipProvider.initiateCall endpoint selection", () => {
  it("dials an extension through the extension endpoint, unnormalised", async () => {
    const calls = stubOriginate();
    await createProvider().initiateCall({
      callId: "c1",
      from: "+815000000700",
      to: "1001",
      webhookUrl: "http://example.invalid/webhook",
    });
    expect(calls[0]?.endpoint).toBe("PJSIP/1001");
  });

  it("still sends an outside number through the trunk, folded to domestic form", async () => {
    const calls = stubOriginate();
    await createProvider().initiateCall({
      callId: "c2",
      from: "+815000000700",
      to: "+818012345678",
      webhookUrl: "http://example.invalid/webhook",
    });
    expect(calls[0]?.endpoint).toBe("Quectel/quectel0/08012345678");
  });

  it("refuses a target that is neither an extension nor a domestic number", async () => {
    stubOriginate();
    await expect(
      createProvider().initiateCall({
        callId: "c3",
        from: "+815000000700",
        to: "+15551234567",
        webhookUrl: "http://example.invalid/webhook",
      }),
    ).rejects.toThrow(/cannot dial/u);
  });
});
