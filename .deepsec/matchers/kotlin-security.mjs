import { regexMatcher } from "deepsec/config";

// These patterns select security-sensitive code for review, not confirmed defects.
const filePatterns = ["app/src/main/java/**/*.kt"];

export const kotlinSecurityPlugin = {
  name: "duplicates-kotlin-security",
  matchers: [
    {
      slug: "duplicates-kotlin-storage-boundary",
      description: "Review canonical paths, symlinks, file identity and byte-level duplicate validation",
      noiseTier: "normal",
      filePatterns,
      examples: [
        "val canonical = File(path).canonicalFile",
        "Files.newByteChannel(path, StandardOpenOption.READ)",
        'val digest = MessageDigest.getInstance("SHA-256")',
      ],
      match(content) {
        return regexMatcher(this.slug, [
          { regex: /\b(?:File|FileInputStream|RandomAccessFile)\s*\(/g, label: "Filesystem access" },
          { regex: /\b(?:Files|Paths|MessageDigest)\s*\./g, label: "File identity or content validation" },
          { regex: /\b(?:canonicalPath|canonicalFile)\b/g, label: "Canonical path boundary" },
        ], content);
      },
    },
    {
      slug: "duplicates-kotlin-deletion-integrity",
      description: "Review keeper preservation, deletion approval, verification and interrupted-deletion recovery",
      noiseTier: "normal",
      filePatterns,
      examples: [
        "members.filter { it.selectedForDeletion }",
        "storage.contentsEqual(first, second)",
        "resultStore.pendingDeletions()",
      ],
      match(content) {
        return regexMatcher(this.slug, [
          {
            regex: /\b(?:selectedForDeletion|recommendedKeep|requiresMediaAuthorization|contentsEqual|fullFingerprint|quickFingerprint|reconcileDeletion|pendingDeletions)\b/g,
            label: "Deletion safety or duplicate verification contract",
          },
        ], content);
      },
    },
    {
      slug: "duplicates-kotlin-platform-access",
      description: "Review Android storage permissions, content providers, URI grants and MediaStore approval",
      noiseTier: "normal",
      filePatterns,
      examples: [
        "class PreviewFileProvider : ContentProvider()",
        "MediaStore.createDeleteRequest(resolver, uris)",
        "Intent(Intent.ACTION_VIEW)",
      ],
      match(content) {
        return regexMatcher(this.slug, [
          {
            regex: /\b(?:ContentProvider|ContentResolver|MediaStore|Intent|ParcelFileDescriptor|StorageManager|Environment)\b/g,
            label: "Android platform access boundary",
          },
        ], content);
      },
    },
    {
      slug: "duplicates-kotlin-persistence",
      description: "Review Room queries, migrations and transaction boundaries for scan/deletion state",
      noiseTier: "normal",
      filePatterns,
      examples: [
        '@Query("SELECT * FROM indexed_files")',
        "database.withTransaction { persistResult() }",
        'database.execSQL("ALTER TABLE files ADD COLUMN selected INTEGER")',
      ],
      match(content) {
        return regexMatcher(this.slug, [
          { regex: /@(?:Dao|Query|Transaction|Database)\b/g, label: "Room persistence contract" },
          { regex: /\b(?:withTransaction|execSQL)\b/g, label: "Transaction or schema boundary" },
        ], content);
      },
    },
  ],
};
