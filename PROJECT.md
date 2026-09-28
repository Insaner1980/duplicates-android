# Duplicates implementation reference

This document describes the implementation in `app/src/main` and its verification code in `app/src/test` and `app/src/androidTest`. The code and generated Room schema are the source of truth.

## Product scope

Duplicates is an English-only, local Android utility for finding and reviewing byte-identical files in mounted shared storage. It scans four user-facing categories: Photos, Videos, Audio, and Documents. Documents is the fallback category for ordinary non-media user files, including archives and installers.

A duplicate is proven by equal byte length, equal full SHA-256, and a final byte-for-byte comparison. Names, paths, extensions, timestamps, image properties, audio tags, and video metadata do not establish duplication.

Deletion is separate from scanning. It requires a user-reviewed selection, preserves at least one valid copy per group, revalidates selected files immediately before mutation, and reports partial results.

### Explicit non-goals

- Similar or near-duplicate media detection, perceptual hashing, burst grouping, and AI classification
- Filename-only, date-only, metadata-only, or folder-based duplicate grouping
- Contact, junk, cache, RAM, battery, antivirus, or general cleaner features
- Cloud scanning, upload, accounts, analytics, advertising, subscriptions, and payments
- User-configured scheduled scans, new scans created at boot, background deletion, or automatic deletion after a scan
- Recycle bin, file restore, or Undo after permanent deletion
- An embedded video or audio player
- Network communication at runtime

## Platform and build

| Item | Current value |
| --- | --- |
| Application ID and namespace | `com.emma.duplicates` |
| App version | `1.0.0` (`versionCode` 1) |
| Module layout | One Android application module, `:app` |
| Minimum SDK | 30, Android 11 |
| Compile and target SDK | 37, Android 17 |
| Java and Kotlin JVM target | 17 |
| Gradle wrapper | 9.7.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.20 |

The manifest enables edge-to-edge window handling through `MainActivity`, predictive back through `enableOnBackInvokedCallback`, a resizable activity, the Android splash screen theme, and a `dataSync` foreground service type for WorkManager. Orientation is not locked. Backup and device transfer are disabled.

### Main libraries

| Responsibility | Library |
| --- | --- |
| UI | Jetpack Compose BOM 2026.09.00, Material 3, Activity Compose 1.13.0 |
| Navigation and state | Navigation Compose 2.10.2, Lifecycle 2.11.0, Kotlin Serialization 1.11.0 |
| Persistence | Room 2.8.5, DataStore Preferences 1.2.1 |
| Background work | WorkManager 2.12.0 |
| Concurrency | Kotlin coroutines 1.11.0 |
| Thumbnails and photos | Coil Compose and Coil Video 3.6.3 |
| Local tests | JUnit 4.13.2, MockK 1.14.11, Turbine 1.2.1, Robolectric 4.17, AndroidX and Compose test libraries |

## Screens and navigation

Navigation uses serializable typed routes with `composable<T>()`. The bottom navigation bar is visible only on Home, Results, and Exclusions. Root navigation uses `saveState` and `restoreState` so root destination state can be restored when switching tabs. Secondary routes do not show the bottom bar.

| Route or state | Presentation and behavior |
| --- | --- |
| `HomeRoute` | Root Home destination. Shows the storage access explanation, Ready to scan, active scan, completed duplicate summary, or no-duplicates state from real permission, preference, Room, and storage-volume state. The gear opens Settings. Category cards open filtered Results. |
| `ResultsRoute(filter)` | Root Results destination. Shows saved totals, search, category filters, sorting, content-based duplicate cards, media thumbnails, and Review actions. Photos show image thumbnails, videos show decoded frames with a play marker, visible audio rows load album artwork when available, and other cases retain the category icon. Search covers group title, member filenames, and parent paths. Sorting supports largest reclaimable space, most copies, filename, newest, and oldest. Clear scan results confirms before deleting Room scan metadata only. |
| `ExclusionsRoute` | Root Exclusions destination. Shows excluded folders and files, removes entries immediately, and opens a single add sheet for folder or file selection. |
| `ScanningRoute` | Shows persisted progress, indexed files, candidate or confirmed group counts, reclaimable space, three phase states, current path, skipped-file note, and failure or cancellation state. All Back actions return to Home without canceling. A failed scan also shows a reason-specific recovery message. Stop requires confirmation and cancels the unique WorkManager request. |
| `ReviewRoute(groupId)` | Shows one confirmed group and all members, the same media-thumbnail behavior as Results, recommendation, keep or delete state, metadata, preview entry, selected total, deletion confirmation, and result dialog. The final valid copy cannot be selected. |
| `PreviewRoute(groupId, fileId)` | Photos use an in-app dark preview with pinch zoom, double-tap zoom, filename, path, size, and date. Other categories show metadata and open a secure local system viewer when available. |
| `SettingsRoute` | Scanning, review and deletion, file type, and About sections. Values come from DataStore, mounted volumes, the real storage permission state, and `BuildConfig.VERSION_NAME`. |
| `ScanLocationsRoute` | Lists only mounted shared volumes with label, total size, free size, and selection state. At least one mounted location must remain selected. |
| `TypesToScanRoute` | Four large rows for Photos, Videos, Audio, and Documents. At least one category must remain enabled. |
| `ExclusionBrowserRoute(kind)` | In-app browser for mounted shared volumes. It keeps navigation inside a selected volume, sorts folders first, returns canonical paths, excludes app-private and restricted Android locations, and reports duplicate, parent-covered, and inaccessible selections. |

Storage permission is a Home state and an Android system settings flow, not a fake in-app toggle. Deletion confirmation and results, scan stop confirmation, result clearing, and the add-exclusion chooser are dialogs or sheets. MediaStore deletion authorization is an Android system dialog.

## Design system

The app is dark-only and does not use dynamic color. It uses the system sans-serif through standard Compose typography.

### Colors

| Token | Value |
| --- | --- |
| Background | `#000000` |
| Surface | `#111111` |
| Surface container low | `#151515` |
| Surface container | `#191919` |
| Surface container high | `#202020` |
| Surface container highest | `#272727` |
| Primary | `#D4AF37` |
| Primary pressed | `#B8962F` |
| Primary focus | `#E0C45F` |
| On primary | `#171200` |
| Primary container | `#3B300A` |
| On primary container | `#F7DE8A` |
| Primary text | `#F5F5F5` |
| Secondary text | `#B8B8B8` |
| Disabled text | `#747474` |
| Outline | `#3A3A3A` |
| Outline variant | `#292929` |
| Other used storage | `#5A5A5A` |
| Free storage | `#242424` |
| Scrim | `#000000` |
| Error | `#FFB4AB` |
| On error | `#690005` |
| Error container | `#93000A` |
| On error container | `#FFDAD6` |

Gold is used for primary actions, selected navigation and filters, active progress, reclaimable-space emphasis, category icons, recommendation, and kept state. Error colors are reserved for destructive actions, selected-for-deletion state, failures, and inaccessible browser messages.

### Typography

| Material style | Size and line height | Weight |
| --- | --- | --- |
| `displayLarge` | 48 sp / 56 sp | Bold |
| `headlineLarge` | 38 sp / 46 sp | Bold |
| `headlineMedium` | 34 sp / 42 sp | Bold |
| `titleLarge` | 24 sp / 32 sp | SemiBold |
| `titleMedium` | 20 sp / 28 sp | SemiBold |
| `bodyLarge` | 17 sp / 26 sp | Normal |
| `bodyMedium` | 16 sp / 24 sp | Normal |
| `bodySmall` | 15 sp / 22 sp | Normal |
| `labelLarge` | 18 sp / 24 sp | SemiBold |
| `labelMedium` | 15 sp / 20 sp | Normal |
| `labelSmall` | 14 sp / 20 sp | Normal |

### Shape and layout tokens

| Token | Value |
| --- | --- |
| Extra-small shape | 16 dp radius |
| Small shape | 18 dp radius |
| Medium shape | 20 dp radius |
| Large shape | 24 dp radius |
| Extra-large shape | 28 dp radius |
| Compact horizontal screen padding | 24 dp |
| Wide horizontal screen padding | 32 dp at 600 dp and wider |
| Standard vertical screen padding | 24 dp |
| Minimum icon action target | 48 dp |
| Settings and browser row minimum | 72 dp |
| Scan location and file-type card minimum | 96 dp |
| Primary button minimum height | 56 dp |
| Primary button content padding | 24 dp horizontal / 16 dp vertical |
| Media thumbnail | 72 dp square |
| Video play badge | 24 dp circle, 16 dp icon, 4 dp bottom and end inset |

`DuplicatesCard` uses Surface container, a 1 dp Outline variant border, 1 dp tonal elevation, and the 24 dp large shape by default. Root navigation uses the 28 dp extra-large shape, transparent selection indicator, gold selected content, and neutral gray unselected content. Screen content scrolls rather than shrinking controls. At high font scale the category grid stacks and root navigation removes outer horizontal padding.

Sizes use decimal units and at most one decimal with `Locale.ENGLISH`. Dates use `MMM d, yyyy, HH:mm`. The storage ring consists of other used bytes, selected reclaimable bytes, and free bytes, so its segments always sum to total storage.

## Package map

| Package | Responsibility |
| --- | --- |
| `com.emma.duplicates` | `MainActivity`, splash, edge-to-edge setup, notification permission request, Activity Result integration, and interrupted-deletion reconciliation gating |
| `app` | Dependency container, typed routes, navigation, ViewModels, scan scheduler and worker, notification factory, UI mapping, and MediaStore deletion authorization |
| `core.designsystem` | Color, typography, shape, card, and primary-action tokens |
| `core.format` | English date and decimal-size formatting and mathematically consistent storage segments |
| `core.model` | File, storage root, exclusion, and exact-duplicate domain data |
| `core.permissions` | Real All files access state and settings intents |
| `core.storage` | Mounted volume enumeration, MediaStore metadata, discovery, category resolution, SHA-256, content comparison, cache contracts, and duplicate scanner |
| `core.database` | Room entities, relations, DAOs, transactions, and database definition |
| `data` | Room-backed stores, fingerprint adapter, result mapper, exclusion repository, storage browser, preview launcher, MediaStore album-art loader, Android direct-deletion adapter, and pending-deletion result store |
| `data.preferences` | DataStore preference model, defaults, flows, and writers |
| `domain.selection` | Deterministic recommendation and keep-one-copy selection policy |
| `domain.deletion` | Revalidation, authorization, direct deletion coordination, pending-operation reconciliation, outcomes, and database update model |
| `ui.components`, `ui.home`, `ui.scan`, `ui.results`, `ui.review`, `ui.exclusions`, `ui.settings`, `ui.preview`, `ui.permission` | Shared UI components, immutable UI state models, and Material 3 Compose screens |

## Architecture and data flow

The app uses constructor injection without a dependency injection framework. `DuplicatesApplication` owns one `AppContainer`. The container supplies the storage permission and volume sources, MediaStore metadata index, DataStore repository, lazy Room database and stores, discovery and exact scanner, exclusions and browser, preview launcher, scan runner, unique-work scheduler, shared deletion storage and result store, a serialized pending-deletion reconciler, and a per-Activity deletion coordinator connected to the MediaStore authorization launcher.

ViewModels receive only the dependencies needed by their screen through `ViewModelFactory`. Repository and Room `Flow` values are combined with local control state into immutable `StateFlow` UI models. Compose collects those flows with lifecycle awareness. User actions call ViewModel methods, which update DataStore, Room, WorkManager, or a domain coordinator. Persisted changes flow back to every affected screen.

The main scan path is:

1. `MainActivity` verifies All files access and requests notification permission when applicable.
2. `ScanScheduler` enqueues the unique `duplicate-scan` one-time work with `ExistingWorkPolicy.KEEP`.
3. `DuplicateScanWorker` calls `ScanRunner` with a new session ID for every work attempt.
4. `ScanRunner` starts a staging Room session, the worker establishes foreground execution through the session-start callback, and the runner then validates access, reads preferences, captures selected mounted roots, refreshes MediaStore metadata, and loads exclusions.
5. `FileDiscovery` enumerates candidates. `ExactDuplicateScanner` compares them and reports progress.
6. `ScanResultMapper` creates indexed files, duplicate groups, member selections, and stable Room IDs.
7. Staged rows are inserted transactionally. A successful session becomes active and replaces the previous completed session. Failure or cancellation leaves the previous active results intact.
8. Room flows update Home, Results, Scanning, Review, and totals.

## Persistence

Room database `duplicates.db` is schema version 3 with schema export enabled. `MIGRATION_1_2` adds the pending-deletion journal and `MIGRATION_2_3` adds the nullable scan failure reason without destructive fallback. The database contains eight tables:

| Table | Purpose and important constraints |
| --- | --- |
| `scan_sessions` | Scan status, start and completion timestamps, active and canceled flags, counts, bytes, current phase and path, progress work, candidate groups, skips, errors, and an actionable failure reason. Active completed results and running or latest session are observable separately. |
| `indexed_files` | One discovered file per session and canonical path. Stores display and location metadata, category, size, last-modified time, MediaStore URI and states, quick and full hashes, access flags, and changed-since-scan state. Cascades with its session. |
| `duplicate_groups` | Confirmed content group keyed uniquely per session by full content hash and file size. Stores category, copy count, selected reclaimable bytes, and a title derived from a real member filename. Cascades with its session. |
| `duplicate_members` | Joins a group to an indexed file and persists recommendation, deletion selection, and auto-selection protection. Both foreign keys cascade. The group plus file pair is unique. |
| `pending_deletion_operations` | One journal header per in-progress deletion. Stores a generated operation ID and creation time. |
| `pending_deletion_items` | Snapshot of each selected member for an operation: group, member and indexed-file IDs, canonical path, size, last-modified time, and optional content URI. The operation plus member is the composite key. Operation and group IDs are indexed, and deleting the operation cascades to its items. |
| `fingerprint_cache` | Persistent quick and full SHA-256 values. Canonical path plus volume is the composite key; size and last-modified time are indexed identity metadata. It is independent of a scan session. |
| `exclusions` | Unique canonical file or folder path, display name, type, and creation time. It is independent of scan sessions. |

File contents and thumbnails are not stored in Room. Clearing scan results deletes scan sessions and their cascading indexed files, groups, and members. It retains exclusions, fingerprint cache entries, and DataStore preferences.

DataStore file `duplicates_settings` contains these preferences:

| Key | Default | Effect |
| --- | --- | --- |
| `scan_hidden_folders` | `false` | Skip any entry whose current name starts with a period |
| `ignore_system_folders` | `true` | Skip conservative technical directory names such as cache, tmp, temp, and lost.dir |
| `auto_select_duplicate_copies` | `true` | Select eligible non-recommended copies when results are created |
| `confirm_before_delete` | `true` | Show the in-app confirmation before the deletion coordinator runs |
| `scan_photos` | `true` | Include the Photos category |
| `scan_videos` | `true` | Include the Videos category |
| `scan_audio` | `true` | Include the Audio category |
| `scan_documents` | `true` | Include Documents and other ordinary user files |
| `selected_volume_ids` | `{ "primary" }` | Select the primary shared volume |

The Settings ViewModel prevents the final mounted volume or final enabled category from being disabled.

## Storage discovery and metadata

`AndroidStorageVolumeSource` reads real `StorageManager.storageVolumes`, keeps mounted and mounted-read-only volumes with a directory, calculates total and free bytes through `StatFs`, places the primary volume first, and supports additional removable volumes when Android exposes them.

Before traversal, `MediaStoreMetadataIndex` reads `MediaStore.Files` for each selected MediaStore volume. It maps normalized paths to content URI, MIME type, favorite state, and trashed state. A missing MediaStore row falls back to filename MIME detection.

`FileDiscovery` runs on `Dispatchers.IO` and uses an iterative FIFO queue. It reads attributes without following links, resolves canonical paths, tracks visited directories, sorts children deterministically, checks coroutine cancellation, and handles missing, unreadable, and inaccessible entries without failing the entire scan.

Discovery excludes:

- Symbolic links, non-regular entries, broken entries, and zero-byte files
- Trashed MediaStore items and known recycle-bin folders
- `Android/data` and `Android/obb`, while leaving `Android/media` eligible
- The app's internal, cache, no-backup, external cache, external files, and external media directories
- User exclusions
- Hidden entries when hidden scanning is off
- Conservative cache and temporary directory names when system-folder filtering is on

Each accepted file records a stable volume and canonical-path ID, filename, extension, MIME type, category, byte length, last-modified time, volume, parent, readability, writability, content URI, favorite state, and trashed state. Image, video, audio, and text MIME families are authoritative. Documents also recognize an allowlist of application document and archive MIME types plus OpenXML and OASIS document families. Other MIME types fall through to known photo, video, and audio extensions; remaining ordinary files become Documents.

## Exact scanner, cache, and staging

The scanner is deterministic and cancellation-aware:

1. Duplicate input locations are removed, files are sorted by canonical path and ID, zero-byte entries are skipped, and exact-size groups with only one member are discarded.
2. Each size candidate receives a quick SHA-256. Eight big-endian bytes of file size are digested first. For a file over 128 KiB, the first and last 64 KiB are sampled. A smaller file is read once in full.
3. Quick-hash singletons are discarded. Remaining files receive a full streamed SHA-256 with 64 KiB buffers. A bounded channel feeds at most two full-hash workers. Progress is byte-weighted for this stage.
4. Full-hash candidates are grouped by exact size and digest. Each group is partitioned through buffered byte comparison against a seed. Only partitions with at least two byte-equal files become results.

The fingerprinter validates size and last-modified time before and after hashing or comparison. Missing, changed, unreadable, and I/O failures become per-file issues. A cached full fingerprint is reused only after identity match and a current snapshot validation. Metadata mismatch or validation failure invalidates the cache entry.

Discovery and scanner progress is written to the staging session and notification at most every 250 ms, except phase changes and completion. Finding files uses indeterminate total work. Hashing uses real item or byte totals. The persisted UI phases are Finding files, Comparing candidates, and Verifying duplicates.

Starting a staging session does not deactivate the previous successful session. At launch, `ScanRunner` snapshots only preference-selected mounted volumes whose directories canonicalize to readable roots. A stale selected ID for a detached volume is ignored when another selected root is usable; no usable selected root returns `EMPTY_SCOPE`. Storage access and the exact captured root identities are checked after discovery and again before activation. Only a fully mapped and transactionally inserted staging result can become active. Completion marks it active and removes older scan sessions. Cancellation marks the staging row canceled in a non-cancellable cleanup block. Failure marks it failed with a persisted reason for removed permission, unavailable volume, full storage, database failure, foreground worker failure, or a generic failure. An orphan running session is failed before a new session starts. Canceled and failed staging sessions leave the previous active result available.

## Background scan execution

`DuplicateScanWorker` is a long-running `CoroutineWorker`. Immediately after the staging session is persisted, its session-start callback calls `setForeground()` before discovery begins. Later progress updates drive the same persisted phase and work measurements shown in the UI. The low-importance `duplicate_scans` notification contains app name, scan label, current phase, determinate or indeterminate progress, a tap target that opens Scanning, and a WorkManager cancel action.

The app's source manifest directly declares:

- `MANAGE_EXTERNAL_STORAGE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS`

WorkManager's merged manifest also contributes `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, and the app-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` used by its runtime. This does not add a Duplicates boot scan or periodic request. The source manifest explicitly removes dependency-contributed `ACCESS_NETWORK_STATE` during manifest merge. The packaged app does not declare `INTERNET`, granular media, camera, microphone, contacts, location, nearby-device, or account permissions.

On Android 13 and newer, `MainActivity` requests notification permission when a scan is started. The callback starts the scan whether permission was allowed or denied, provided storage access still exists. The foreground service remains required, but Android can omit its notification from the drawer after denial.

A new worker request is enqueued only from visible user actions. The app defines no periodic request, app-owned boot receiver, or retry policy. WorkManager contributes its reschedule receiver and can resume an unfinished persisted one-time request after a process or device restart. The unique-work KEEP policy prevents parallel scans. UI Back does not cancel a scan. Only the explicit UI Stop action or notification Stop action cancels it.

## Deterministic selection

`DuplicateSelectionPolicy` chooses one recommended keep member with this comparator:

1. Valid and readable copy
2. MediaStore favorite
3. `DCIM/Camera`, Pictures, Music, Movies, or Documents location
4. Location outside Download, messaging, cache, and temporary directories
5. Present, readable, and writable copy
6. Earliest positive last-modified time
7. Shortest normalized path
8. Case-insensitive path, case-sensitive path, then stable ID

Favorites and explicitly protected members are never auto-selected. With auto-selection enabled, other writable valid members are selected except the recommendation. With it disabled, no member is preselected. The recommendation remains informational and users may keep more than one copy.

Selection changes are persisted in `duplicate_members`. The DAO rejects selecting a member when no other readable, unchanged, unselected copy exists. It then recalculates group and session reclaimable bytes from selected, readable, writable, unchanged members.

## Deletion safety

The deletion path is user initiated from Review:

1. The ViewModel reads the current persisted group and selected members.
2. The default in-app dialog summarizes selected file count and selected bytes. The `confirm_before_delete` preference can disable this app dialog, but the Review selection remains required and Android authorization still applies where required.
3. `DeletionCoordinator` snapshots every group member. A matching size and last-modified time accepts current membership. Changed metadata triggers full SHA-256 and byte comparison against another readable group member.
4. Missing, changed, unreadable, non-writable, or failed-revalidation selected files are kept or recorded as vanished. No selected file proceeds unless a readable matching unselected keeper exists.
5. Before mutation, `RoomDeletionResultStore` writes an operation and every selected group, member, indexed-file, path, identity, and content-URI snapshot to the pending-deletion journal.
6. Files that do not require MediaStore authorization are deleted one by one with `Files.deleteIfExists`, then checked for absence. After a successful path deletion, an available stored content URI is removed from MediaStore on a best-effort basis to avoid a stale row. MediaStore-authorized items use `MediaStore.createDeleteRequest` in chunks of at most 2,000 URIs and are checked after the system result. Room state is checkpointed after every direct deletion and every approved MediaStore chunk.
7. The result distinguishes deleted, vanished, changed, revalidation failed, inaccessible, authorization canceled, direct failure, and blocked-no-keeper outcomes.
8. Normal completion uses one Room transaction to remove deleted or vanished files and members, remove changed members from duplicate groups, drop groups with fewer than two matching members, update surviving counts and selected reclaimable bytes, recalculate session totals, and delete the journal operation.

If the process ends during deletion, `MainActivity` requests reconciliation from an orphan Activity result or from `onResume` only when no live authorization continuation exists. `DeletionRecoveryGate` prevents the live callback and following resume from handling the same request twice. `PendingDeletionReconciler` serializes concurrent calls, reads every journaled path, and treats only `snapshot == null` as deleted. One Room transaction removes those matching members and indexed files, updates or drops affected groups, recalculates session totals, and removes the journal. Existing files are left in the saved results.

Deletion does not claim cross-file atomicity. The result dialog reports deleted and failed counts and explains changed files or canceled Android authorization. Deleted bytes are counted only after verified absence. There is no recycle bin or file recovery path.

## Exclusions

Exclusions store canonical paths. A candidate must exist, be readable, have the requested file or folder type, and remain under a currently mounted shared root. Exact duplicates are rejected. A parent folder exclusion covers descendants. Adding a broader folder removes redundant descendant exclusions. Removal updates the Room flow immediately.

The browser canonicalizes every opened path, prevents traversal outside the mounted root, ignores symbolic and non-regular entries, hides app-specific directories plus `Android/data`, `Android/obb`, and recycle folders, and lists only readable entries. File mode shows folders and files. Folder mode shows folders and exposes Select this folder.

## Preview behavior

Results and Review use one 72 dp `MediaThumbnail` component with the category icon as its fallback. Photos load through Coil from the saved content URI or canonical path. Videos use a 72 dp request with Coil's explicit `VideoFrameDecoder`, including for extensionless MediaStore URIs, and add a Primary gold play icon over a Surface badge at 88 percent alpha. Audio artwork is requested only for currently visible lazy-list rows. `AudioAlbumArtLoader` converts the stored MediaStore Files URI to the volume-specific Audio Media URI, queries `ALBUM_ID`, builds the matching album URI, and calls `ContentResolver.loadThumbnail` on `Dispatchers.IO`. Missing rows, artwork, access, or provider support leave the Audio icon in place. Documents keep their category icon.

Photos are rendered with Coil from the MediaStore content URI or canonical path. Gesture scale is clamped from 1x through 5x; double tap toggles 1x and 2x. Preview is read-only.

Videos, audio, and documents use the generic metadata screen and a system `ACTION_VIEW` chooser. When a MediaStore content URI was stored, it is opened read-only to confirm that it is usable; a stale URI produces the missing state. Only a file with no stored content URI uses the readable canonical file through the non-exported FileProvider paths for shared storage. The viewer receives a `content://` URI through both intent data and `ClipData`, with temporary read permission. A specific stored MIME type is preferred; generic MIME values fall back to extension lookup and then `application/octet-stream`. Unsupported, missing, provider, and no-compatible-viewer cases remain non-destructive and produce an explanatory state. No file is copied for preview and no `file://` URI is exposed.

## Tests and runtime verification

The local JUnit suite covers scanner, discovery, hashing, cache reuse and invalidation, Room transactions, staging and specific failure reasons, background worker outcomes, selection, deletion, MediaStore authorization and cleanup, interrupted-deletion reconciliation, preview URI preparation, media thumbnails and album-art loading, permissions, preferences, ViewModels, formatters, string policy, and Robolectric Compose screens. Compose coverage includes scan-start and View scan actions, stop and delete confirmation, the exact preview callback target, deletion-result counts and freed space, empty and completed Home states, Results interactions, exclusions, settings, and 200 percent font scale. Fakes and temporary directories isolate file-system and deletion tests from personal files.

The string resource policy test rejects U+2014, U+2013, U+00B7, and U+2022. Room schema tests cover relationships, activation, selection guards, deletion updates, pending-operation journaling and reconciliation, and clear behavior. Scanner tests cover same and different content, quick-hash collisions, final byte comparison, file changes and removal, cancellation, cache behavior, and deterministic grouping. Deletion tests cover direct and authorized deletion, partial failure, changed or vanished files, canceled authorization, keeper enforcement, checkpointing, process interruption, reconciliation serialization and gating, and transactional totals.

Verification commands are:

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

The final connected run contains two passing tests on `Duplicates_Pixel9_API37(AVD) - 17`: `MainActivityTest` verifies launch, the storage permission explanation, navigation to Results, the empty-results state, and return to Home, while `DuplicatesDatabaseMigrationTest` validates the Room 2 to 3 upgrade.

Manual runtime verification on the same Pixel 9 API 37 Android 17 AVD used only isolated `DuplicatesQa-20260812` fixture directories under `/sdcard/DCIM`, `/sdcard/Pictures`, and `/sdcard/Movies`. A real scan indexed 9 files into 3 exact groups. Photo preview opened, Android authorization was approved for one 1.2 MB duplicate, the file disappeared, and Room-backed results and totals updated. Notification denial still allowed the scan to continue in the background. Notification allowance showed the ongoing notification, its tap opened Scanning, and Stop canceled work. A canceled scan preserved earlier completed results. The UI was also checked at 200 percent font scale and in landscape, and exclusion add and remove were exercised. All three fixture directories were removed afterward and their absence was verified.

Scanner and deletion edge cases are additionally validated with isolated local tests rather than unrelated device files.

The interrupted-deletion journal and reconciliation paths are covered by local tests. The production database builder registers `MIGRATION_1_2` and `MIGRATION_2_3`, exported schemas 1 through 3 are present in the source tree, and `MigrationTestHelper` validates the 2 to 3 upgrade on Android 17. No recorded device run forces an operating-system process kill during a live MediaStore authorization.

The debug APK output is `app/build/outputs/apk/debug/app-debug.apk`.
