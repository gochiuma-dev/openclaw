// Voice Call plugin module implements SIP behavior via Asterisk AudioSocket.
import crypto from "node:crypto";
import net from "node:net";
import type {
  GetCallStatusInput,
  GetCallStatusResult,
  HangupCallInput,
  InitiateCallInput,
  InitiateCallResult,
  NormalizedEvent,
  PlayTtsInput,
  ProviderWebhookParseResult,
  StartListeningInput,
  StopListeningInput,
  WebhookContext,
  WebhookParseOptions,
  WebhookVerificationResult,
} from "../types.js";
import { createWebhookReplayCache, reserveWebhookReplay } from "../webhook-replay.js";
import type { VoiceCallProvider } from "./base.js";

/**
 * SIP provider. Asterisk owns signalling; this provider owns the media.
 *
 * Why not speak SIP here: Asterisk already terminates SIP, RTP, codecs and NAT.
 * AudioSocket hands us raw 8 kHz signed-linear frames over TCP, so the provider
 * only has to decide where an utterance ends and push audio back.
 *
 * Speech-to-text and text-to-speech live behind one authenticated relay
 * (voisona-relay on the Mac): POST /stt returns text, POST /wav returns audio.
 * whisper itself stays bound to loopback there; it has no authentication.
 *
 * AudioSocket framing: 1 byte kind, 2 byte big-endian length, payload.
 *   0x00 terminate | 0x01 uuid(16) | 0x10 audio | 0xff error
 * Asterisk sends one 20 ms frame (320 bytes) at a time.
 */

const KIND_TERMINATE = 0x00;
const KIND_UUID = 0x01;
const KIND_AUDIO = 0x10;
const KIND_ERROR = 0xff;

const SAMPLE_RATE = 8000;
const FRAME_BYTES = 320; // 20 ms of 16-bit mono
const FRAME_MS = 20;

export type SipProviderOptions = {
  /** Where the AudioSocket server listens for Asterisk. */
  bind: string;
  port: number;
  /** voisona-relay base URL; owns both /stt and /wav. */
  relayUrl: string;
  relayUser: string;
  relayPassword: string;
  /** Silence that ends an utterance. Too short clips words, too long stalls replies. */
  silenceMs: number;
  /** Frames quieter than this count as silence. */
  silenceRms: number;
  /** Utterances shorter than this are noise, not speech. */
  minSpeechMs: number;
  maxUtteranceMs: number;
  /** Pushes provider events into the manager. Replaceable after construction. */
  onEvent: (event: NormalizedEvent) => void;
  logger?: { info: (m: string) => void; warn: (m: string) => void };
};

type CallState = {
  callId: string;
  providerCallId: string;
  socket: net.Socket;
  from?: string;
  to?: string;
  /** Buffered speech for the utterance in flight. */
  speech: Buffer[];
  speechBytes: number;
  speaking: boolean;
  silenceMs: number;
  listening: boolean;
  /** Playback owns the channel; captured audio during playback is echo. */
  playing: boolean;
  transcribing: boolean;
  /** Feeds silence while nobody is speaking; Asterisk hangs up on a starved socket. */
  keepalive?: NodeJS.Timeout;
  /** TTS synthesis may run ahead of playback, but audio must remain FIFO. */
  ttsTail: Promise<void>;
};

/** Pending metadata the dialplan posts before AudioSocket connects. */
type PendingCall = { from?: string; to?: string; at: number };

const PENDING_TTL_MS = 60_000;

export class SipProvider implements VoiceCallProvider {
  readonly name = "sip" as const;

  private readonly opts: SipProviderOptions;
  private logger?: { info: (m: string) => void; warn: (m: string) => void };
  private server?: net.Server;
  private readonly calls = new Map<string, CallState>();
  private readonly pending = new Map<string, PendingCall>();
  private readonly replayCache = createWebhookReplayCache();

  private sink: (event: NormalizedEvent) => void;

  constructor(opts: SipProviderOptions) {
    this.opts = opts;
    this.sink = opts.onEvent;
  }

  /**
   * The webhook server does not exist yet when the provider is constructed,
   * so the event sink is attached afterwards. Call before start() or the first
   * call is dropped.
   */
  setEventSink(sink: (event: NormalizedEvent) => void): void {
    this.sink = sink;
  }

  setLogger(logger: { info: (m: string) => void; warn: (m: string) => void }): void {
    this.logger = logger;
  }

  // ---------------------------------------------------------------- lifecycle

  async start(): Promise<void> {
    if (this.server) {
      return;
    }
    const server = net.createServer((socket) => this.handleSocket(socket));
    this.server = server;
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(this.opts.port, this.opts.bind, () => {
        server.off("error", reject);
        resolve();
      });
    });
    this.log(`AudioSocket listening on ${this.opts.bind}:${this.opts.port}`);
  }

  async stop(): Promise<void> {
    for (const call of this.calls.values()) {
      call.socket.destroy();
    }
    this.calls.clear();
    const server = this.server;
    this.server = undefined;
    if (server) {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }

  // ------------------------------------------------------------------ webhook

  /**
   * The dialplan posts caller metadata here before handing the channel to
   * AudioSocket, because AudioSocket carries only the UUID. Without this the
   * per-caller session key would have nothing to key on.
   */
  verifyWebhook(ctx: WebhookContext): WebhookVerificationResult {
    // The server rejects a verification that carries no identity key, because
    // it has nothing to dedupe replays against. Asterisk does not sign this
    // request, so the request itself is the key.
    const material = `${ctx.method}\n${ctx.url}\n${ctx.rawBody}`;
    const key = `sip:${crypto.createHash("sha256").update(material).digest("hex")}`;
    return { ok: true, ...reserveWebhookReplay(this.replayCache, key) };
  }

  parseWebhookEvent(
    ctx: WebhookContext,
    _options?: WebhookParseOptions,
  ): ProviderWebhookParseResult {
    try {
      const params = new URLSearchParams(ctx.rawBody);
      const uuid = normalizeUuid(params.get("uuid") ?? "");
      if (!uuid) {
        return { events: [], statusCode: 400 };
      }
      this.prunePending();
      this.pending.set(uuid, {
        from: params.get("from")?.trim() || undefined,
        to: params.get("to")?.trim() || undefined,
        at: Date.now(),
      });
      this.log(`call metadata registered for ${uuid}`);
      return { events: [], statusCode: 200 };
    } catch {
      return { events: [], statusCode: 400 };
    }
  }

  // ----------------------------------------------------------------- provider

  async initiateCall(_input: InitiateCallInput): Promise<InitiateCallResult> {
    // Outbound needs Asterisk to originate the channel (AMI/ARI); inbound is
    // the only direction wired today. Failing loudly beats a silent no-op.
    throw new Error("SIP provider does not place outbound calls yet");
  }

  async hangupCall(input: HangupCallInput): Promise<void> {
    const call = this.findCall(input.callId, input.providerCallId);
    if (!call) {
      return;
    }
    if (call.keepalive) {
      clearInterval(call.keepalive);
      call.keepalive = undefined;
    }
    this.writeFrame(call, KIND_TERMINATE, Buffer.alloc(0));
    call.socket.end();
  }

  async playTts(input: PlayTtsInput): Promise<void> {
    const call = this.findCall(input.callId, input.providerCallId);
    if (!call) {
      return;
    }
    const text = input.text.trim();
    if (!text) {
      return;
    }
    // Start synthesis before waiting for earlier audio to finish. This is the
    // important part of sentence-level pre-synthesis: sentence N+1 is being
    // rendered while sentence N is streamed to Asterisk.
    const audioPromise = this.synthesize(text, input.voice);
    // The queue may be busy playing an earlier sentence. Attach a rejection
    // handler now as synthesis can finish (or fail) before that sentence's
    // playback slot is reached.
    void audioPromise.catch(() => undefined);
    const playback = call.ttsTail.then(async () => {
      const audio = await audioPromise;
      if (!audio.length || call.socket.destroyed) {
        return;
      }
      call.playing = true;
      try {
        await this.streamAudio(call, audio);
      } finally {
        // Whatever the microphone picked up while we spoke is our own voice.
        call.speech = [];
        call.speechBytes = 0;
        call.speaking = false;
        call.silenceMs = 0;
        call.playing = false;
      }
    });
    // Keep the chain alive after one failed sentence so a later queued
    // sentence is still attempted and no unhandled rejection is produced.
    call.ttsTail = playback.catch(() => undefined);
    await playback;
  }

  async startListening(input: StartListeningInput): Promise<void> {
    const call = this.findCall(input.callId, input.providerCallId);
    if (call) {
      call.listening = true;
    }
  }

  async stopListening(input: StopListeningInput): Promise<void> {
    const call = this.findCall(input.callId, input.providerCallId);
    if (call) {
      call.listening = false;
    }
  }

  async getCallStatus(input: GetCallStatusInput): Promise<GetCallStatusResult> {
    const call = this.calls.get(input.providerCallId);
    return call
      ? { status: "in-progress", isTerminal: false }
      : { status: "completed", isTerminal: true };
  }

  // -------------------------------------------------------------------- media

  private handleSocket(socket: net.Socket): void {
    socket.setNoDelay(true);
    let buffer = Buffer.alloc(0);
    let call: CallState | undefined;

    socket.on("data", (chunk: Buffer) => {
      buffer = buffer.length ? Buffer.concat([buffer, chunk]) : Buffer.from(chunk);
      for (;;) {
        if (buffer.length < 3) {
          return;
        }
        const kind = buffer[0]!;
        const length = buffer.readUInt16BE(1);
        if (buffer.length < 3 + length) {
          return;
        }
        const payload = buffer.subarray(3, 3 + length);
        buffer = buffer.subarray(3 + length);

        if (kind === KIND_UUID) {
          call = this.openCall(socket, payload);
          continue;
        }
        if (kind === KIND_TERMINATE || kind === KIND_ERROR) {
          socket.end();
          return;
        }
        if (kind === KIND_AUDIO && call) {
          this.consumeAudio(call, payload);
        }
      }
    });

    const close = () => {
      if (!call) {
        return;
      }
      if (call.keepalive) {
        clearInterval(call.keepalive);
        call.keepalive = undefined;
      }
      this.calls.delete(call.providerCallId);
      this.emit({
        type: "call.ended",
        callId: call.callId,
        providerCallId: call.providerCallId,
        reason: "completed",
      });
      this.log(`call ${call.providerCallId} ended`);
      call = undefined;
    };
    socket.on("close", close);
    socket.on("error", close);
  }

  private openCall(socket: net.Socket, payload: Buffer): CallState {
    const providerCallId = formatUuid(payload);
    const meta = this.pending.get(providerCallId);
    this.pending.delete(providerCallId);
    const call: CallState = {
      callId: providerCallId,
      providerCallId,
      socket,
      from: meta?.from,
      to: meta?.to,
      speech: [],
      speechBytes: 0,
      speaking: false,
      silenceMs: 0,
      listening: true,
      playing: false,
      transcribing: false,
      ttsTail: Promise.resolve(),
    };
    this.calls.set(providerCallId, call);
    // Asterisk reads this socket continuously and drops the call when a read
    // finds nothing ("Failed to read type header"). Keep a 20 ms silence
    // cadence running whenever we are not speaking.
    const silence = Buffer.alloc(FRAME_BYTES);
    call.keepalive = setInterval(() => {
      if (call.playing || call.socket.destroyed) {
        return;
      }
      this.writeFrame(call, KIND_AUDIO, silence);
    }, FRAME_MS);
    this.log(`call ${providerCallId} from ${call.from ?? "unknown"}`);
    this.emit({
      type: "call.answered",
      callId: call.callId,
      providerCallId,
      direction: "inbound",
      from: call.from,
      to: call.to,
    });
    return call;
  }

  /** Decides where an utterance ends, then hands it to the transcriber. */
  private consumeAudio(call: CallState, frame: Buffer): void {
    if (!call.listening || call.playing || call.transcribing) {
      return;
    }
    const loud = rms(frame) >= this.opts.silenceRms;
    if (loud) {
      call.speaking = true;
      call.silenceMs = 0;
      call.speech.push(Buffer.from(frame));
      call.speechBytes += frame.length;
      return;
    }
    if (!call.speaking) {
      return;
    }
    // Keep trailing silence so the last syllable is not clipped.
    call.speech.push(Buffer.from(frame));
    call.speechBytes += frame.length;
    call.silenceMs += FRAME_MS;

    const spokenMs = (call.speechBytes / 2 / SAMPLE_RATE) * 1000;
    if (call.silenceMs < this.opts.silenceMs && spokenMs < this.opts.maxUtteranceMs) {
      return;
    }
    const pcm = Buffer.concat(call.speech);
    call.speech = [];
    call.speechBytes = 0;
    call.speaking = false;
    call.silenceMs = 0;
    if (spokenMs < this.opts.minSpeechMs) {
      return;
    }
    void this.transcribeUtterance(call, pcm);
  }

  private async transcribeUtterance(call: CallState, pcm: Buffer): Promise<void> {
    call.transcribing = true;
    try {
      const text = await this.transcribe(pcm);
      if (!text) {
        return;
      }
      this.log(`call ${call.providerCallId} heard ${JSON.stringify(text.slice(0, 40))}`);
      this.emit({
        type: "call.speech",
        callId: call.callId,
        providerCallId: call.providerCallId,
        transcript: text,
        isFinal: true,
        from: call.from,
        to: call.to,
      });
    } catch (error) {
      this.warn(`transcription failed: ${String(error)}`);
    } finally {
      call.transcribing = false;
    }
  }

  /** Paces playback at real time; Asterisk drops frames that arrive in a burst. */
  private async streamAudio(call: CallState, pcm: Buffer): Promise<void> {
    for (let offset = 0; offset < pcm.length; offset += FRAME_BYTES) {
      if (call.socket.destroyed) {
        return;
      }
      const slice = pcm.subarray(offset, offset + FRAME_BYTES);
      const frame =
        slice.length === FRAME_BYTES
          ? slice
          : Buffer.concat([slice, Buffer.alloc(FRAME_BYTES - slice.length)]);
      this.writeFrame(call, KIND_AUDIO, frame);
      await sleep(FRAME_MS);
    }
  }

  private writeFrame(call: CallState, kind: number, payload: Buffer): void {
    if (call.socket.destroyed) {
      return;
    }
    const header = Buffer.alloc(3);
    header[0] = kind;
    header.writeUInt16BE(payload.length, 1);
    call.socket.write(Buffer.concat([header, payload]));
  }

  // -------------------------------------------------------------------- relay

  private async transcribe(pcm: Buffer): Promise<string> {
    const res = await this.relay("/stt", wrapWav(pcm), "audio/wav");
    const body = (await res.json()) as { text?: string };
    return (body.text ?? "").trim();
  }

  private async synthesize(text: string, voice?: string): Promise<Buffer> {
    const payload: Record<string, unknown> = {
      text,
      rate: SAMPLE_RATE,
      format: "slin",
    };
    if (voice) {
      payload.voice_name = voice;
    }
    const res = await this.relay("/wav", Buffer.from(JSON.stringify(payload)), "application/json");
    return Buffer.from(await res.arrayBuffer());
  }

  private async relay(path: string, body: Buffer, contentType: string): Promise<Response> {
    const auth = Buffer.from(`${this.opts.relayUser}:${this.opts.relayPassword}`).toString(
      "base64",
    );
    const res = await fetch(`${this.opts.relayUrl}${path}`, {
      method: "POST",
      headers: { Authorization: `Basic ${auth}`, "Content-Type": contentType },
      // このリポジトリの lib 設定では BodyInit にバイト列が含まれない。
      // 実行時（undici）は受け付けるので、ここだけ型を通す。
      body: new Uint8Array(body.buffer, body.byteOffset, body.byteLength) as unknown as BodyInit,
    });
    if (!res.ok) {
      throw new Error(`relay ${path} returned ${res.status}`);
    }
    return res;
  }

  // ------------------------------------------------------------------ helpers

  private findCall(callId: string, providerCallId?: string): CallState | undefined {
    if (providerCallId) {
      const byProvider = this.calls.get(providerCallId);
      if (byProvider) {
        return byProvider;
      }
    }
    return this.calls.get(callId);
  }

  /**
   * 判別共用体に Omit は効かないので、ここで id と timestamp を足してから
   * 1 度だけ型を通す。呼ぶ側は type ごとの必須項目を書くだけでよい。
   */
  private emit(event: Record<string, unknown> & { type: NormalizedEvent["type"] }): void {
    this.sink({
      id: crypto.randomUUID(),
      timestamp: Date.now(),
      ...event,
    } as unknown as NormalizedEvent);
  }

  private prunePending(): void {
    const cutoff = Date.now() - PENDING_TTL_MS;
    for (const [uuid, entry] of this.pending) {
      if (entry.at < cutoff) {
        this.pending.delete(uuid);
      }
    }
  }

  private log(message: string): void {
    (this.logger ?? this.opts.logger)?.info(`[voice-call/sip] ${message}`);
  }

  private warn(message: string): void {
    (this.logger ?? this.opts.logger)?.warn(`[voice-call/sip] ${message}`);
  }
}

/** Loudness of a 16-bit mono frame. */
function rms(frame: Buffer): number {
  const samples = Math.floor(frame.length / 2);
  if (!samples) {
    return 0;
  }
  let sum = 0;
  for (let i = 0; i < samples; i += 1) {
    const value = frame.readInt16LE(i * 2);
    sum += value * value;
  }
  return Math.sqrt(sum / samples);
}

/** The relay's /stt expects a container, not bare samples. */
function wrapWav(pcm: Buffer): Buffer {
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVEfmt ", 8);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(SAMPLE_RATE, 24);
  header.writeUInt32LE(SAMPLE_RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

function formatUuid(payload: Buffer): string {
  const hex = payload.subarray(0, 16).toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

function normalizeUuid(value: string): string {
  const trimmed = value.trim().toLowerCase();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(trimmed)
    ? trimmed
    : "";
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
