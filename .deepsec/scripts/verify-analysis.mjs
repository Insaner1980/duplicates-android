import { createHash } from "node:crypto";
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
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

export function verify(projectRoot = fileURLToPath(new URL("../../", import.meta.url))) {
  const dataRoot = path.join(projectRoot, ".deepsec/data/duplicates-android");
  const readJson = file => JSON.parse(readFileSync(file, "utf8"));
  const scan = jsonFiles(path.join(dataRoot, "runs")).map(readJson)
    .filter(run => run.type === "scan" && run.phase === "done")
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
  if (!scan) throw new Error("No completed DeepSec scan.");
  const records = jsonFiles(path.join(dataRoot, "files"))
    .map(file => ({ file, record: readJson(file) }))
    .filter(({ record }) => record.lastScannedRunId === scan.runId);
  const kotlinCount = records.filter(({ record }) => record.filePath.endsWith(".kt")).length;
  if (records.length !== scan.stats.filesScanned || kotlinCount === 0) {
    throw new Error("Missing scan records or no Kotlin review candidates.");
  }
  const receipt = readJson(path.join(dataRoot, "source-scan.json"));
  const problems = [];
  const uncovered = [];
  for (const { file, record } of records) {
    const expected = receipt.files?.[record.filePath];
    if (!expected) {
      uncovered.push(record.filePath);
      continue;
    }
    const actual = createHash("sha256").update(readFileSync(path.join(projectRoot, record.filePath))).digest("hex");
    const reason = actual === expected ? incompleteReason(record) : "source changed since the current secrets scan";
    if (!reason) continue;
    problems.push(`${record.filePath}: ${reason}`);
    if (record.status === "analyzed") {
      record.status = "error";
      writeFileSync(file, JSON.stringify(record, null, 2) + "\n");
    }
  }
  const errors = [];
  if (uncovered.length > 0) errors.push(`Files outside the secrets scan receipt; align scan scope before retrying (statuses unchanged):\n${uncovered.join("\n")}`);
  if (problems.length > 0) errors.push(`Incomplete DeepSec review; affected records will be retried:\n${problems.join("\n")}`);
  if (errors.length > 0) throw new Error(errors.join("\n"));
  console.log(`DeepSec review verified: ${records.length} candidate files, including ${kotlinCount} Kotlin files; no skipped or failed investigations.`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { verify(); } catch (error) { console.error(error.message); process.exitCode = 2; }
}
