import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  appendNotificationLogEntry,
  resolveNotificationForwardingPolicy,
  resolveNotificationLogDay,
  shouldLogNotification,
  shouldWakeForNotification,
  type NotificationLogEntry,
} from "./notification-log.js";

function buildEntry(overrides: Partial<NotificationLogEntry> = {}): NotificationLogEntry {
  return {
    ts: "2026-09-22T10:15:00.000Z",
    change: "posted",
    nodeId: "node-1",
    key: "0|com.example.chat|1|null|10000",
    packageName: "com.example.chat",
    title: "Message",
    text: "Ping",
    sessionKey: "agent:main:main",
    woke: false,
    ...overrides,
  };
}

describe("resolveNotificationForwardingPolicy", () => {
  it("keeps the legacy wake-per-notification behavior when unset", () => {
    const policy = resolveNotificationForwardingPolicy(undefined, "/state");
    expect(policy.mode).toBe("wake");
    expect(policy.logDir).toBe(path.join("/state", "notifications"));
    expect(shouldLogNotification(policy)).toBe(false);
    expect(shouldWakeForNotification(policy, "com.example.chat")).toBe(true);
  });

  it("falls back to wake for an unrecognized mode rather than dropping events", () => {
    const policy = resolveNotificationForwardingPolicy(
      { mode: "batched" as unknown as "log" },
      "/state",
    );
    expect(policy.mode).toBe("wake");
  });

  it("logs without waking in log mode", () => {
    const policy = resolveNotificationForwardingPolicy({ mode: "log" }, "/state");
    expect(shouldLogNotification(policy)).toBe(true);
    expect(shouldWakeForNotification(policy, "com.example.chat")).toBe(false);
  });

  it("wakes only for listed packages in hybrid mode, case-insensitively", () => {
    const policy = resolveNotificationForwardingPolicy(
      { mode: "hybrid", wakePackages: ["  com.google.android.Dialer  ", ""] },
      "/state",
    );
    expect(shouldLogNotification(policy)).toBe(true);
    expect(shouldWakeForNotification(policy, "com.google.android.dialer")).toBe(true);
    expect(shouldWakeForNotification(policy, "com.example.chat")).toBe(false);
  });

  it("treats hybrid with an empty wake list as log-only", () => {
    const policy = resolveNotificationForwardingPolicy(
      { mode: "hybrid", wakePackages: [] },
      "/state",
    );
    expect(shouldWakeForNotification(policy, "com.example.chat")).toBe(false);
    expect(shouldWakeForNotification(policy, null)).toBe(false);
  });

  it("honors an explicit log directory", () => {
    const policy = resolveNotificationForwardingPolicy(
      { mode: "log", logDir: "/var/log/notif" },
      "/state",
    );
    expect(policy.logDir).toBe("/var/log/notif");
  });
});

describe("resolveNotificationLogDay", () => {
  it("partitions by the requested timezone, not UTC", () => {
    // 22:15 UTC is already the next calendar day in Tokyo.
    const at = new Date("2026-09-22T22:15:00.000Z");
    expect(resolveNotificationLogDay(at, "Asia/Tokyo")).toBe("2026-09-23");
    expect(resolveNotificationLogDay(at, "UTC")).toBe("2026-09-22");
  });
});

describe("appendNotificationLogEntry", () => {
  it("appends one JSON line per event into the day's file", async () => {
    const dir = await mkdtemp(path.join(tmpdir(), "notif-log-"));
    const policy = resolveNotificationForwardingPolicy({ mode: "log", logDir: dir }, "/state");

    await appendNotificationLogEntry(policy, buildEntry(), "UTC");
    await appendNotificationLogEntry(
      policy,
      buildEntry({ change: "removed", title: "", text: "" }),
      "UTC",
    );

    const contents = await readFile(path.join(dir, "2026-09-22.jsonl"), "utf8");
    const lines = contents.trim().split("\n");
    expect(lines).toHaveLength(2);
    expect(JSON.parse(lines[0] ?? "")).toMatchObject({
      change: "posted",
      packageName: "com.example.chat",
      woke: false,
    });
    expect(JSON.parse(lines[1] ?? "")).toMatchObject({ change: "removed" });
  });

  it("creates the log directory on demand", async () => {
    const base = await mkdtemp(path.join(tmpdir(), "notif-log-"));
    const nested = path.join(base, "deep", "nested");
    const policy = resolveNotificationForwardingPolicy({ mode: "log", logDir: nested }, "/state");

    await appendNotificationLogEntry(policy, buildEntry(), "UTC");

    await expect(readFile(path.join(nested, "2026-09-22.jsonl"), "utf8")).resolves.toContain(
      "com.example.chat",
    );
  });

  it("keeps serving later appends after one write fails", async () => {
    const dir = await mkdtemp(path.join(tmpdir(), "notif-log-"));
    // A path whose parent is an existing file cannot be created as a directory.
    const blocked = resolveNotificationForwardingPolicy({ mode: "log", logDir: dir }, "/state");
    await appendNotificationLogEntry(blocked, buildEntry(), "UTC");

    const broken = resolveNotificationForwardingPolicy(
      { mode: "log", logDir: path.join(dir, "2026-09-22.jsonl", "sub") },
      "/state",
    );
    await expect(appendNotificationLogEntry(broken, buildEntry(), "UTC")).rejects.toThrow();

    await appendNotificationLogEntry(blocked, buildEntry({ change: "removed" }), "UTC");
    const contents = await readFile(path.join(dir, "2026-09-22.jsonl"), "utf8");
    expect(contents.trim().split("\n")).toHaveLength(2);
  });

  it("falls back to the current day when the entry timestamp is unparseable", async () => {
    const dir = await mkdtemp(path.join(tmpdir(), "notif-log-"));
    const policy = resolveNotificationForwardingPolicy({ mode: "log", logDir: dir }, "/state");

    await appendNotificationLogEntry(policy, buildEntry({ ts: "not-a-date" }), "UTC");

    const today = resolveNotificationLogDay(new Date(), "UTC");
    await expect(readFile(path.join(dir, `${today}.jsonl`), "utf8")).resolves.toContain(
      "not-a-date",
    );
  });
});
