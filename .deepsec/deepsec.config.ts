import { defineConfig } from "deepsec/config";

export default defineConfig({
  projects: [
    {
      id: "duplicates-android",
      root: "..",
      promptAppend:
        "Review this offline exact-duplicate file utility. Prioritize canonical path and symlink boundaries, storage access, byte-for-byte duplicate validation, preservation of a readable keeper before deletion, MediaStore authorization, interrupted-deletion journal recovery, FileProvider URI grants, backup exclusions, and sensitive path logging. The app intentionally requests MANAGE_EXTERNAL_STORAGE for user-initiated shared-storage scans and has no INTERNET permission. Assess permissions in that context. Read source only; do not modify code, delete files, run the app, or access connected devices or personal storage.",
      priorityPaths: [
        "app/src/main/AndroidManifest.xml",
        "app/src/main/res/xml/",
        "app/src/main/java/com/emma/duplicates/core/storage/",
        "app/src/main/java/com/emma/duplicates/domain/deletion/",
        "app/src/main/java/com/emma/duplicates/data/",
        "app/src/main/java/com/emma/duplicates/app/AndroidMediaDeletionAuthorizer.kt",
        "app/src/main/java/com/emma/duplicates/MainActivity.kt"
      ]
    }
  ]
});
