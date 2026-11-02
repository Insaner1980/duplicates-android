import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { incompleteReason, verify } from "../scripts/verify-analysis.mjs";

const completed = {
  status: "analyzed",
  analysisHistory: [{ usage: { outputTokens: 100 }, refusal: { refused: false, skipped: [] } }],
};

test("completed zero-finding investigations are accepted", () => {
  assert.equal(incompleteReason(completed), null);
});

test("technical skips cannot become clean merely because refused is false", () => {
  const record = structuredClone(completed);
  record.analysisHistory[0].refusal.skipped = [{ filePath: "Storage.kt", reason: "Required scanner unavailable" }];
  assert.match(incompleteReason(record), /skipped/);
});

test("refusals and capacity-error JSON repairs are incomplete", () => {
  const record = structuredClone(completed);
  record.analysisHistory[0].refusal.refused = true;
  assert.match(incompleteReason(record), /refused/);
  delete record.analysisHistory[0].usage;
  assert.match(incompleteReason(record), /missing completed investigation/);
});

test("pending, running and failed records do not count as completed", () => {
  for (const status of ["pending", "processing", "error"]) {
    assert.match(incompleteReason({ ...completed, status }), /status=/);
  }
  assert.match(incompleteReason({ status: "analyzed", analysisHistory: [] }), /missing/);
});

test("uncovered records remain unchanged while changed covered sources are marked for retry", () => {
  const root = mkdtempSync(fileURLToPath(new URL(".verify-fixture-", import.meta.url)));
  try {
    const dataRoot = path.join(root, ".deepsec/data/duplicates-android");
    mkdirSync(path.join(dataRoot, "runs"), { recursive: true });
    mkdirSync(path.join(dataRoot, "files"));
    const writeJson = (file, value) => writeFileSync(file, JSON.stringify(value));
    writeJson(path.join(dataRoot, "runs/scan.json"), {
      type: "scan", phase: "done", runId: "test-scan", createdAt: "2026-11-02T00:00:00Z", stats: { filesScanned: 2 },
    });
    const uncoveredPath = path.join(dataRoot, "files/uncovered.json");
    const coveredPath = path.join(dataRoot, "files/covered.json");
    const uncovered = { ...completed, filePath: "outside.kt", lastScannedRunId: "test-scan" };
    const covered = { ...completed, filePath: "covered.kt", lastScannedRunId: "test-scan" };
    writeJson(uncoveredPath, uncovered);
    writeJson(coveredPath, covered);
    writeFileSync(path.join(root, "covered.kt"), "original");
    writeJson(path.join(dataRoot, "source-scan.json"), {
      files: { "covered.kt": createHash("sha256").update("original").digest("hex") },
    });

    // The uncovered source is deliberately absent: verification must not try to read it.
    assert.throws(() => verify(root), /outside the secrets scan receipt[\s\S]*outside\.kt/);
    assert.deepEqual(JSON.parse(readFileSync(uncoveredPath, "utf8")), uncovered);
    assert.deepEqual(JSON.parse(readFileSync(coveredPath, "utf8")), covered);

    writeFileSync(path.join(root, "covered.kt"), "changed");
    assert.throws(() => verify(root), /covered.kt: source changed since the current secrets scan/);
    assert.deepEqual(JSON.parse(readFileSync(uncoveredPath, "utf8")), uncovered);
    assert.equal(JSON.parse(readFileSync(coveredPath, "utf8")).status, "error");
  } finally {
    rmSync(root, { recursive: true });
  }
});
