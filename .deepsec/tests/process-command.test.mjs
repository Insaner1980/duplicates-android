import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const workspace = fileURLToPath(new URL("../", import.meta.url));
const manifest = JSON.parse(readFileSync(path.join(workspace, "package.json"), "utf8"));

test("process command satisfies the shared workspace validator contract", () => {
  assert.match(manifest.scripts["deepsec:process"], /^deepsec process /);
  assert.doesNotMatch(manifest.scripts["deepsec:process"], /[&|;\r\n]/);
  assert.deepEqual([...manifest.scripts["deepsec:process"].matchAll(/--concurrency\s+(\S+)/g)].map(match => match[1]), ["1"]);
});

test("pnpm runs the coverage guard before processing and stops when it fails", () => {
  const root = mkdtempSync(path.join(workspace, "tests/.process-fixture-"));
  try {
    mkdirSync(path.join(root, "scripts"));
    const scripts = { ...manifest.scripts };
    // Replace the external AI boundary; retain the real package lifecycle wiring.
    scripts["deepsec:process"] = scripts["deepsec:process"].replace(/^deepsec process /, "node process.mjs ");
    writeFileSync(path.join(root, "package.json"), JSON.stringify({ ...manifest, scripts }));
    copyFileSync(path.join(workspace, "pnpm-workspace.yaml"), path.join(root, "pnpm-workspace.yaml"));
    writeFileSync(path.join(root, "scripts/verify-analysis.mjs"),
      'import fs from "node:fs"; fs.writeFileSync("guard.txt", process.argv.slice(2).join(" ")); process.exit(Number(process.env.GUARD_EXIT));');
    writeFileSync(path.join(root, "process.mjs"),
      'import fs from "node:fs"; if (fs.readFileSync("guard.txt", "utf8") !== "--prepare") process.exit(3); if (process.argv[process.argv.indexOf("--manifest") + 1] !== "./data/duplicates-android/process-manifest.json") process.exit(4); fs.writeFileSync("processed.txt", "done");');
    for (const exitCode of [2, 0]) {
      rmSync(path.join(root, "guard.txt"), { force: true });
      const result = spawnSync(process.platform === "win32" ? "cmd.exe" : "pnpm",
        process.platform === "win32" ? ["/d", "/s", "/c", "pnpm --config.verify-deps-before-run=false run deepsec:process"] : ["--config.verify-deps-before-run=false", "run", "deepsec:process"],
        { cwd: root, env: { ...process.env, GUARD_EXIT: String(exitCode) }, encoding: "utf8", timeout: 30000 });
      assert.ifError(result.error);
      assert.ok(existsSync(path.join(root, "guard.txt")), result.stdout + result.stderr);
      assert.equal(readFileSync(path.join(root, "guard.txt"), "utf8"), "--prepare");
      assert.equal(result.status === 0, exitCode === 0, result.stdout + result.stderr);
      assert.equal(existsSync(path.join(root, "processed.txt")), exitCode === 0);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
