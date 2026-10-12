import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { resolveClaudeCliProjectsDir } from "./claude-cli-project-dir.js";

describe("resolveClaudeCliProjectsDir", () => {
  it("uses CLAUDE_CONFIG_DIR in place of ~/.claude", () => {
    const configDir = path.join(os.tmpdir(), "claude-config");
    expect(
      resolveClaudeCliProjectsDir(undefined, { HOME: "/home/user", CLAUDE_CONFIG_DIR: configDir }),
    ).toBe(path.join(configDir, "projects"));
  });

  it("falls back to HOME when CLAUDE_CONFIG_DIR is empty", () => {
    expect(
      resolveClaudeCliProjectsDir(undefined, { HOME: "/home/user", CLAUDE_CONFIG_DIR: " " }),
    ).toBe(path.join("/home/user", ".claude", "projects"));
  });

  it("keeps an explicit homeDir ahead of CLAUDE_CONFIG_DIR", () => {
    expect(
      resolveClaudeCliProjectsDir("/explicit", { HOME: "/home/user", CLAUDE_CONFIG_DIR: "/cfg" }),
    ).toBe(path.join("/explicit", ".claude", "projects"));
  });
});
