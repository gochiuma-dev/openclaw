// Durable log for notifications forwarded by device nodes.
//
// Forwarded notifications used to exist only as system events, which live in a
// bounded in-memory queue (see system-events.ts) and are lost on restart. That
// is fine when every event immediately wakes an agent, but it makes batching
// unsafe: anything past the queue cap disappears with no trace. Batched modes
// therefore append here first, so a scheduled job can read the full history
// later without the gateway having woken an agent per notification.

import { appendFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { resolveNotificationLogDir } from "../config/paths.js";
import type { GatewayNodeNotificationsConfig } from "../config/types.gateway.js";

export type NotificationForwardingMode = "wake" | "log" | "hybrid";

export type NotificationForwardingPolicy = {
  mode: NotificationForwardingMode;
  /** Packages that still wake immediately under `hybrid`. Lowercased. */
  wakePackages: ReadonlySet<string>;
  logDir: string;
};

export type NotificationLogEntry = {
  /** Event time in ISO-8601 with offset. */
  ts: string;
  change: "posted" | "removed";
  nodeId: string;
  key: string;
  packageName: string | null;
  title: string;
  text: string;
  /** Session the legacy path would have queued this event onto. */
  sessionKey: string;
  /** True when this event also woke an agent. */
  woke: boolean;
};

const DEFAULT_MODE: NotificationForwardingMode = "wake";

function normalizeMode(raw: unknown): NotificationForwardingMode {
  const value = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  return value === "log" || value === "hybrid" || value === "wake" ? value : DEFAULT_MODE;
}

/**
 * Resolves the effective forwarding policy. Unset config keeps the legacy
 * wake-per-notification behavior so existing installs are unaffected.
 */
export function resolveNotificationForwardingPolicy(
  cfg?: GatewayNodeNotificationsConfig,
  stateDir?: string,
): NotificationForwardingPolicy {
  const wakePackages = new Set(
    (cfg?.wakePackages ?? [])
      .map((pkg) => (typeof pkg === "string" ? pkg.trim().toLowerCase() : ""))
      .filter((pkg) => pkg.length > 0),
  );
  const configuredDir = typeof cfg?.logDir === "string" ? cfg.logDir.trim() : "";
  return {
    mode: normalizeMode(cfg?.mode),
    wakePackages,
    logDir: configuredDir || resolveNotificationLogDir(stateDir),
  };
}

/** Decides whether one notification should wake the owning agent. */
export function shouldWakeForNotification(
  policy: NotificationForwardingPolicy,
  packageName: string | null,
): boolean {
  if (policy.mode === "wake") {
    return true;
  }
  if (policy.mode === "log") {
    return false;
  }
  // hybrid: only the operator-listed packages are worth an immediate turn.
  return packageName ? policy.wakePackages.has(packageName.toLowerCase()) : false;
}

/** Decides whether one notification should be written to the durable log. */
export function shouldLogNotification(policy: NotificationForwardingPolicy): boolean {
  return policy.mode !== "wake";
}

/** Formats the day partition for a timestamp, in the host's local calendar. */
export function resolveNotificationLogDay(at: Date = new Date(), timeZone?: string): string {
  // en-CA renders ISO-shaped YYYY-MM-DD, which keeps files sortable by name.
  return new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    ...(timeZone ? { timeZone } : {}),
  }).format(at);
}

/** Resolves the log file one entry belongs in. */
export function resolveNotificationLogPath(
  policy: NotificationForwardingPolicy,
  at: Date = new Date(),
  timeZone?: string,
): string {
  return path.join(policy.logDir, `${resolveNotificationLogDay(at, timeZone)}.jsonl`);
}

// Appends are serialized per process so concurrent node events cannot interleave
// partial lines within one file.
let writeChain: Promise<void> = Promise.resolve();

/**
 * Appends one entry to the durable log. Resolves once the line is on disk.
 * Rejects on write failure so the caller can decide whether to fall back to a
 * wake rather than lose the notification entirely.
 */
export function appendNotificationLogEntry(
  policy: NotificationForwardingPolicy,
  entry: NotificationLogEntry,
  timeZone?: string,
): Promise<void> {
  const at = new Date(entry.ts);
  const target = resolveNotificationLogPath(
    policy,
    Number.isNaN(at.valueOf()) ? new Date() : at,
    timeZone,
  );
  const line = `${JSON.stringify(entry)}\n`;
  const next = writeChain.then(async () => {
    await mkdir(path.dirname(target), { recursive: true });
    await appendFile(target, line, "utf8");
  });
  // Keep the chain alive after a failed append so one bad write cannot wedge
  // every later notification.
  writeChain = next.catch(() => undefined);
  return next;
}
