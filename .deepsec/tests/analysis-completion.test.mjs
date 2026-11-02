import assert from "node:assert/strict";
import { test } from "node:test";
import { incompleteReason } from "../scripts/verify-analysis.mjs";

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
