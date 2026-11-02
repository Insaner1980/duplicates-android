import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { matchesGlob } from "node:path";
import { test } from "node:test";
import { kotlinSecurityPlugin } from "../matchers/kotlin-security.mjs";

const matchers = kotlinSecurityPlugin.matchers;

for (const matcher of matchers) {
  test(`${matcher.slug} recognizes its review examples without regex state leaking`, () => {
    for (const example of matcher.examples) {
      assert.ok(matcher.match(example).length > 0, example);
      assert.ok(matcher.match(example).length > 0, example);
    }
    assert.equal(matcher.match("data class UiLabel(val title: String)").length, 0);
  });
}

test("Kotlin matcher scope excludes generated files and test code", () => {
  for (const matcher of matchers) {
    assert.ok(matcher.filePatterns.some(pattern => matchesGlob("app/src/main/java/example/Storage.kt", pattern)));
    for (const file of [
      "reports/Storage.kt",
      "app/build/generated/Storage.kt",
      "app/src/test/java/example/Storage.kt",
      "app/src/androidTest/java/example/Storage.kt",
    ]) {
      assert.ok(!matcher.filePatterns.some(pattern => matchesGlob(file, pattern)), file);
    }
  }
});

test("actual storage, authorization, keeper and recovery implementations have review candidates", () => {
  const root = new URL("../../app/src/main/java/com/emma/duplicates/", import.meta.url);
  for (const file of [
    "MainActivity.kt",
    "app/AndroidMediaDeletionAuthorizer.kt",
    "core/permissions/StorageAccessManager.kt",
    "core/storage/FileDiscovery.kt",
    "core/storage/FileFingerprinter.kt",
    "core/storage/ExactDuplicateScanner.kt",
    "core/storage/MediaStoreMetadataIndex.kt",
    "core/database/Daos.kt",
    "core/database/DuplicatesDatabase.kt",
    "data/PreviewFileProvider.kt",
    "data/FilePreviewLauncher.kt",
    "data/AndroidDeletionStorage.kt",
    "data/RoomDeletionResultStore.kt",
    "data/ScanStore.kt",
    "domain/deletion/DeletionCoordinator.kt",
    "domain/deletion/PendingDeletionReconciler.kt",
    "domain/selection/DuplicateSelectionPolicy.kt",
  ]) {
    const content = readFileSync(new URL(file, root), "utf8");
    assert.ok(matchers.some(matcher => matcher.match(content).length > 0), file);
  }
});

test("legacy scan exclusions remove artifacts without excluding production code or manifests", () => {
  const config = JSON.parse(readFileSync(new URL("../data/duplicates-android/config.json", import.meta.url), "utf8"));
  for (const file of ["reports/dependency-check-report.html", ".gradle/cache/config.json", ".deepsec/data/history.json"]) {
    assert.ok(config.ignorePaths.some(pattern => matchesGlob(file, pattern)), file);
  }
  for (const file of ["app/src/main/java/example/Storage.kt", "app/src/main/AndroidManifest.xml", ".github/workflows/codeql.yml"]) {
    assert.ok(!config.ignorePaths.some(pattern => matchesGlob(file, pattern)), file);
  }
});
