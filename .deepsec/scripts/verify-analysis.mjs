import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export function incompleteReason(record) {
  if (record.status !== "analyzed") return `status=${record.status}`;
  const history = record.analysisHistory?.at(-1);
  if (!history || !(history.usage?.outputTokens > 0)) return "missing completed investigation evidence";
  if (history.refusal?.refused || history.refusal?.skipped?.length > 0) return "investigation refused or skipped";
  return null;
}

function jsonFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const name = path.join(directory, entry.name);
    if (entry.isDirectory()) return jsonFiles(name);
    return entry.isFile() && entry.name.endsWith(".json") ? [name] : [];
  });
}

export function verify(projectRoot = fileURLToPath(new URL("../../", import.meta.url)), { prepare = false } = {}) {
  const dataRoot = path.join(projectRoot, ".deepsec/data/duplicates-android");
  const readJson = file => JSON.parse(readFileSync(file, "utf8"));
  const scan = jsonFiles(path.join(dataRoot, "runs")).map(readJson)
    .filter(run => run.type === "scan" && run.phase === "done")
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
  if (!scan) throw new Error("No completed DeepSec scan.");
  const allRecords = jsonFiles(path.join(dataRoot, "files"))
    .map(file => ({ file, record: readJson(file) }));
  const records = allRecords.filter(({ record }) => record.lastScannedRunId === scan.runId);
  const kotlinCount = records.filter(({ record }) => record.filePath.endsWith(".kt")).length;
  if (records.length !== scan.stats.filesScanned || kotlinCount === 0) {
    throw new Error("Missing scan records or no Kotlin review candidates.");
  }
  const receipt = readJson(path.join(dataRoot, "source-scan.json"));
  // Keep coverage state outside FileRecord: DeepSec's schema strips unknown fields.
  const blockedPath = path.join(dataRoot, "coverage-blocked.json");
  const blocked = existsSync(blockedPath) ? readJson(blockedPath) : {};
  const saveBlocked = () => writeFileSync(blockedPath, JSON.stringify(blocked, null, 2) + "\n");
  const candidates = prepare ? allRecords : records;
  if (prepare && candidates.some(({ record }) => record.status === "processing")) {
    throw new Error("DeepSec records are still processing; do not change their coverage state during an active run.");
  }
  const problems = [];
  const uncovered = [];
  for (const { file, record } of candidates) {
    const expected = receipt.files?.[record.filePath];
    if (!expected) {
      blocked[record.filePath] = {};
      saveBlocked();
      uncovered.push(record.filePath);
      continue;
    }
    const actual = createHash("sha256").update(readFileSync(path.join(projectRoot, record.filePath))).digest("hex");
    if (actual !== expected) {
      blocked[record.filePath] = {};
      saveBlocked();
      problems.push(`${record.filePath}: source changed since the current secrets scan`);
      continue;
    }
    const marker = blocked[record.filePath];
    if (prepare) {
      if (marker) {
        blocked[record.filePath] = { sourceHash: expected, previousRunId: record.analysisHistory?.at(-1)?.runId ?? null };
        saveBlocked();
        record.status = "pending";
        writeFileSync(file, JSON.stringify(record, null, 2) + "\n");
      }
      continue;
    }
    const latestRunId = record.analysisHistory?.at(-1)?.runId;
    const needsFreshAnalysis = marker && (marker.sourceHash !== expected || !latestRunId || latestRunId === marker.previousRunId);
    const reason = needsFreshAnalysis ? "analysis must be rerun after receipt coverage" : incompleteReason(record);
    if (!reason && marker) {
      delete blocked[record.filePath];
      saveBlocked();
    }
    if (!reason) continue;
    problems.push(`${record.filePath}: ${reason}`);
    if (record.status === "analyzed") {
      record.status = "error";
      writeFileSync(file, JSON.stringify(record, null, 2) + "\n");
    }
  }
  const errors = [];
  if (uncovered.length > 0) errors.push(`Files outside the secrets scan receipt; align scan scope before retrying (statuses unchanged, coverage blocked):\n${uncovered.join("\n")}`);
  if (problems.length > 0) errors.push(`Incomplete DeepSec review; prepare covered sources before retrying:\n${problems.join("\n")}`);
  if (errors.length > 0) throw new Error(errors.join("\n"));
  if (prepare) console.log(`DeepSec processing coverage verified: ${candidates.length} files.`);
  else console.log(`DeepSec review verified: ${records.length} candidate files, including ${kotlinCount} Kotlin files; no skipped or failed investigations.`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { verify(undefined, { prepare: process.argv.includes("--prepare") }); } catch (error) { console.error(error.message); process.exitCode = 2; }
}
