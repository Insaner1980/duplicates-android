import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { incompleteReason, verify } from "../scripts/verify-analysis.mjs";

const completed = {
  status: "analyzed",
  analysisHistory: [{ runId: "old-analysis", usage: { outputTokens: 100 }, refusal: { refused: false, skipped: [] } }],
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

test("uncovered analyses require preparation and a fresh investigation after coverage expands", () => {
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
    const receiptPath = path.join(dataRoot, "source-scan.json");
    const receipt = { files: { "covered.kt": createHash("sha256").update("original").digest("hex") } };
    writeJson(receiptPath, receipt);
    const readJson = file => JSON.parse(readFileSync(file, "utf8"));
    const blockedPath = path.join(dataRoot, "coverage-blocked.json");

    // The uncovered source is deliberately absent: verification must not try to read it.
    assert.throws(() => verify(root), /outside the secrets scan receipt[\s\S]*outside\.kt/);
    assert.deepEqual(JSON.parse(readFileSync(uncoveredPath, "utf8")), uncovered);
    assert.deepEqual(JSON.parse(readFileSync(coveredPath, "utf8")), covered);
    assert.deepEqual(readJson(blockedPath), { "outside.kt": {} });

    // Default processing can also select pending/error records from older scans.
    for (const status of ["analyzed", "pending", "error"]) {
      const olderRecord = { ...uncovered, status, lastScannedRunId: "previous-scan" };
      writeJson(uncoveredPath, olderRecord);
      writeJson(path.join(dataRoot, "runs/scan.json"), {
        type: "scan", phase: "done", runId: "test-scan", createdAt: "2026-11-02T00:00:00Z", stats: { filesScanned: 1 },
      });
      assert.throws(() => verify(root, { prepare: true }), /outside the secrets scan receipt/);
      assert.deepEqual(readJson(uncoveredPath), olderRecord);
    }
    writeJson(uncoveredPath, uncovered);
    writeJson(path.join(dataRoot, "runs/scan.json"), {
      type: "scan", phase: "done", runId: "test-scan", createdAt: "2026-11-02T00:00:00Z", stats: { filesScanned: 2 },
    });

    writeFileSync(path.join(root, "outside.kt"), "newly covered");
    receipt.files["outside.kt"] = createHash("sha256").update("newly covered").digest("hex");
    writeJson(receiptPath, receipt);
    assert.throws(() => verify(root), /analysis must be rerun after receipt coverage/);
    verify(root, { prepare: true });
    assert.equal(readJson(uncoveredPath).status, "pending");
    assert.deepEqual(readJson(uncoveredPath).analysisHistory, uncovered.analysisHistory);

    // Merely restoring analyzed status must not make the old investigation valid.
    writeJson(uncoveredPath, uncovered);
    assert.throws(() => verify(root), /analysis must be rerun after receipt coverage/);
    writeJson(uncoveredPath, {
      ...uncovered,
      analysisHistory: [...uncovered.analysisHistory, { ...completed.analysisHistory[0], runId: "fresh-analysis" }],
    });
    verify(root);
    assert.deepEqual(readJson(blockedPath), {});
    verify(root, { prepare: true });
    assert.equal(readJson(uncoveredPath).status, "analyzed");

    writeFileSync(path.join(root, "covered.kt"), "changed");
    assert.throws(() => verify(root), /covered.kt: source changed since the current secrets scan/);
    assert.deepEqual(readJson(blockedPath), { "covered.kt": {} });
    assert.throws(() => verify(root, { prepare: true }), /source changed since the current secrets scan/);
    assert.deepEqual(readJson(coveredPath), covered);
    receipt.files["covered.kt"] = createHash("sha256").update("changed").digest("hex");
    writeJson(receiptPath, receipt);
    assert.throws(() => verify(root), /analysis must be rerun after receipt coverage/);
    verify(root, { prepare: true });
    assert.equal(readJson(coveredPath).status, "pending");

    const processing = { ...covered, status: "processing", lockedByRunId: "active-run" };
    writeJson(coveredPath, processing);
    assert.throws(() => verify(root, { prepare: true }), /still processing/);
    assert.deepEqual(readJson(coveredPath), processing);
  } finally {
    rmSync(root, { recursive: true });
  }
});
