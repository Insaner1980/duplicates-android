# Duplicates implementation reference

This document describes the implementation in `app/src/main`, its verification code in `app/src/test` and `app/src/androidTest`, and the checked-in build and CI configuration. The code, Gradle files, workflow files, and generated Room schema are the source of truth.

Storage cleanup source update: **2026-11-03**. Current expansion evidence is tracked in `docs/storage-cleanup-validation.md`; the historical verification sections below retain their original dates and limits. The expansion was reviewed at commit `5ec850023505de942d6b9ae0f428e4a6717d8b69`, including the earlier Results selection-clarity changes. This reference does not certify a published release or an APK built from a particular commit. The previous documentation-only review on **2026-11-02** inspected existing reports without running tests; the expansion report records the new implementation checks separately.

## Using this reference for reviews, tests, and UI work

- For code-review questions, identify the owning source and the relevant test below, then verify the current implementation. Product intent, implemented guards, test assertions, and observed device behavior are different kinds of evidence.
- For test questions, use the coverage map and verification status near the end. A test's presence is not a passing run; a Robolectric pass is not Android device or TalkBack evidence. Generated reports can be stale or overwritten by a filtered run.
- For UI questions, use the screen map, design system, and Results semantics together. Preserve the distinction between potential savings, selected bytes, and bytes actually deleted. Preserve the current palette and interaction model unless a requested change explicitly alters them.

All source paths in the following map are relative to `app/src/main/java/com/emma/duplicates/`, except where a full repository-relative path is shown. Local test classes live under the corresponding package in `app/src/test/java/com/emma/duplicates/`; instrumentation tests live in `app/src/androidTest/java/com/emma/duplicates/`.

| Question or change area | Start with these sources | Focused test entry points |
| --- | --- | --- |
| Startup, permissions, themes, navigation | `MainActivity.kt`, `app/Routes.kt`, `app/AppNavigation.kt`, `app/AppDestinations.kt`, `app/AppNavigationActions.kt` | `app/AppNavigationTest.kt`, `app/PermissionViewModelTest.kt`, `DeletionRecoveryGateTest.kt` |
| Dependency wiring and state ownership | `app/DuplicatesApplication.kt`, `app/ViewModelFactory.kt`, the affected `app/*ViewModel.kt` | Matching ViewModel test |
| Scope selection and discovery boundaries | `app/ScanLocationsViewModel.kt`, `data/StorageBrowser.kt`, `core/storage/FileDiscovery.kt`, `app/ScanRunner.kt` | `app/ScanLocationsViewModelTest.kt`, `app/StorageBrowserViewModelTest.kt`, `core/storage/FileDiscoveryTest.kt`, `app/ScanRunnerTest.kt` |
| Exact identity, hashing, cache | `core/storage/ExactDuplicateScanner.kt`, `core/storage/FileFingerprinter.kt`, `data/RoomFingerprintCache.kt` | `core/storage/ExactDuplicateScannerTest.kt`, `core/storage/Sha256FileFingerprinterTest.kt`, `data/RoomFingerprintCacheTest.kt` |
| Scan lifecycle, progress, previous-result preservation | `app/ScanRunner.kt`, `app/DuplicateScanWorker.kt`, `app/ScanWork.kt`, `app/ScanViewModel.kt`, `data/ScanStore.kt` | `app/ScanRunnerTest.kt`, `app/DuplicateScanWorkerTest.kt`, `app/ScanCompletionTest.kt`, `data/ScanStoreTest.kt` |
| Results counts, search, filters, selection | `app/ResultsViewModel.kt`, `app/DuplicateResultsMapper.kt`, `domain/selection/DuplicateSelectionPolicy.kt`, `core/database/Daos.kt` | `app/ResultsViewModelTest.kt`, `app/ResultsDeletionTest.kt`, `app/DuplicateResultsMapperTest.kt`, `domain/selection/DuplicateSelectionPolicyTest.kt` |
| Recoverable deletion and interruption recovery | `domain/deletion/DeletionCoordinator.kt`, `domain/deletion/PendingDeletionReconciler.kt`, `data/AndroidDeletionStorage.kt`, `data/RoomDeletionResultStore.kt`, `app/AndroidMediaDeletionAuthorizer.kt`, `MainActivity.kt` | `domain/deletion/DeletionCoordinatorTest.kt`, `domain/deletion/PendingDeletionReconcilerTest.kt`, `data/AndroidDeletionStorageTest.kt`, `data/RoomDeletionResultStoreTest.kt`, `app/AndroidMediaDeletionAuthorizerTest.kt`, `DeletionRecoveryGateTest.kt` |
| Database shape and transactions | `core/database/Entities.kt`, `core/database/Daos.kt`, `core/database/DuplicatesDatabase.kt`, `app/schemas/` | `core/database/DuplicatesDatabaseTest.kt`, `data/ScanStoreTest.kt`; instrumented `core/database/DuplicatesDatabaseMigrationTest.kt` |
| UI layout, semantics, copy, thumbnails | `core/designsystem/`, `ui/components/`, `ui/results/`, the affected screen, `app/src/main/res/values/strings.xml` | `ui/results/ResultsScreenTest.kt`, `ui/home/ReadyHomeScreenTest.kt`, `ui/SettingsLayoutTest.kt`, `ui/ScanSelectionScreenTest.kt`, `ui/components/MediaThumbnailTest.kt`, `StringResourcePolicyTest.kt` |
| Preview and external read grants | `app/PreviewViewModel.kt`, `ui/preview/PreviewScreens.kt`, `data/FilePreviewLauncher.kt`, `data/PreviewFileProvider.kt` | `data/FilePreviewLauncherTest.kt`, `data/PreviewFileProviderTest.kt`, `ui/PresentationScreensTest.kt` |
| Exclusions and preferences | `data/ExclusionRepository.kt`, `data/preferences/PreferencesRepository.kt`, `app/ExclusionsViewModel.kt`, `app/SettingsViewModel.kt` | `app/ExclusionsViewModelTest.kt`, `app/SettingsViewModelTest.kt`, `data/preferences/PreferencesRepositoryTest.kt` |

The older UI redesign plan in `docs/superpowers/plans/2026-11-01-duplicates-ui-redesign.md` is planning context. Resolve differences using the current source and verified evidence, rather than treating the plan as proof of implementation.

## Product scope

Duplicates is an English-only, local Android utility for finding and reviewing byte-identical files in mounted shared storage. It scans four user-facing categories: Photos, Videos, Audio, and Documents. Documents is the fallback category for ordinary non-media user files, including archives and installers.

A duplicate is proven by equal byte length, equal full SHA-256, and a final byte-for-byte comparison. Names, paths, extensions, timestamps, image properties, audio tags, and video metadata do not establish duplication.

Deletion is separate from scanning. It uses the persisted selection, applies a keep-one-valid-copy guard and batch revalidation before mutation, and reports partial results. Auto-selection is enabled by default; it does not delete anything until the user invokes deletion. The exact revalidation limits are described under Deletion safety.

The storage cleanup expansion adds Empty folders, Large files, Storage usage, Similar photos, Screenshots, Old downloads, and Unused apps. New results are never automatically selected. `ToolScanRunner` and `ToolScanWorker` share unique WorkManager work with exact scanning; `ToolResultStore` preserves per-tool sessions. `CleanupCoordinator` verifies ordinary files against their own frozen SHA-256 and identity and journals a same-volume recovery move. `EmptyFolderDeletionCoordinator` uses `SecureDirectoryStream.deleteDirectory` with no file-deletion fallback. Exact, ordinary, directory and recovery mutations share the operation mutex and pending-operation gates.

`SimilarPhotoScanner` decodes bounded, orientation-aware samples and computes dHash64, rejecting low-detail inputs. Seven-band candidates and complete-link grouping enforce pairwise distance at most six and aspect-ratio difference at most five percent. Favorite, resolution, size and stable path ordering choose the initial keeper; preview can change it. `UnusedAppsRepository` uses Android usage history and StorageStats only on demand. App and data bytes are added without adding cache again; absent or insufficient history is unavailable, not proof of disuse.

### Explicit non-goals

- AI classification and automatic burst-photo decisions
- Filename-only, date-only, metadata-only, or folder-based duplicate grouping
- Contact, junk, cache, RAM, battery, antivirus, or general cleaner features
- Cloud scanning, upload, accounts, analytics, advertising, subscriptions, and payments
- User-configured scheduled scans, new scans created at boot, background deletion, or automatic deletion after a scan
- Undo after recovery copies have expired or app data has been removed
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
| Gradle wrapper | 9.8.2 |
| Android Gradle Plugin | 9.5.0 |
| Kotlin | 2.4.21 |

Versions above are declared project versions, not claims that they are the latest available releases. `app/build.gradle.kts` sets both the JVM target and Kotlin toolchain to 17. The JVM that launches Gradle or Detekt is a separate environment choice; the historical JDK limitation below must not be mistaken for a change to the app's bytecode target.

Release enables R8 minification and resource shrinking with `app/proguard-rules.pro`; debug enables unit-test coverage. A debug build or local JUnit pass does not establish release shrinking, signing, installation, or store readiness. Root `build.gradle.kts` also overrides selected transitive dependency versions through `securityDependencyVersions`, so the version catalog alone is not a complete resolved dependency inventory.

`MainActivity` enables edge-to-edge window handling. The manifest declares predictive back through `enableOnBackInvokedCallback`, a resizable activity, the Android splash screen theme, and a `dataSync` foreground service type for WorkManager. Orientation is not locked. Backup and device transfer are disabled.

### Main libraries

| Responsibility | Library |
| --- | --- |
| UI | Jetpack Compose BOM 2026.09.00, Material 3, Activity Compose 1.13.0 |
| Navigation and state | Navigation Compose 2.10.2, Lifecycle 2.11.0, Kotlin Serialization 1.12.0 |
| Persistence | Room 2.8.5, DataStore Preferences 1.3.0 |
| Background work | WorkManager 2.12.0 |
| Concurrency | Kotlin coroutines 1.11.0 |
| Thumbnails and photos | Coil Compose and Coil Video 3.6.3 |
| Local tests | JUnit 4.13.2, MockK 1.14.11, Turbine 1.2.1, Robolectric 4.17, AndroidX and Compose test libraries |

### Continuous integration

`.github/workflows/codeql.yml` is the only checked-in GitHub Actions workflow. It runs CodeQL analysis for `java-kotlin` on pushes and pull requests targeting `main`, plus the weekly schedule `37 4 * * 1`. The job uses `ubuntu-latest`, Temurin Java 17, CodeQL manual build mode, and `bash gradlew :app:assembleDebug --no-daemon` before analysis. Its declared permissions are read-only repository contents and write access to security events.

The CodeQL workflow proves that its analysis build completed when that hosted job passes. It does not run the local JUnit suite, Android lint, connected tests, or a release build; those remain separate verification gates.

`.github/dependabot.yml` configures dependency updates separately. Neither the workflow definition nor this document establishes the current hosted CodeQL result or open-alert count; those were not fetched for this update.

## Screens and navigation

Navigation uses serializable typed routes with `composable<T>()`. Home, Tools, and Recovery form the bottom navigation. Home and Tools open the seven tool destinations; Home storage opens Storage usage. `DuplicateSetupRoute` owns the existing exact-scan settings and entry points. Successful scanning opens the same completed session only after its persisted results are active. Results replaces Scanning while preserving its setup and root origin. Exclusions is reached through Settings.

| Route or state | Presentation and behavior |
| --- | --- |
| `HomeRoute` | Real selected-volume storage, View storage usage, seven concise tool rows, and Settings. Storage access can be granted from Home or a file tool. |
| `ToolsRoute`, `ToolRoute(tool, runId)`, `ToolPreviewRoute(sessionId, itemId)` | Tool menu, persisted tool results and controls, and ordinary-file preview by stable identity. Back returns through the originating stack. |
| `DuplicateSetupRoute` | Existing exact scan locations, categories, previous result, scan progress and start controls. |
| `ResultsRoute(filter)` | Scrollable potential-savings summary, duplicate group/member counts, search, category filters, sorting, and every member with its exact preview and persisted selection. A fixed footer shows global selected files/bytes and discloses hidden selections. Clear results removes scan metadata only after confirmation. |
| `ExclusionsRoute` | Settings child destination. Shows excluded folders and files, removes entries immediately, and opens a single add sheet for folder or file selection. |
| `ScanningRoute(baselineSessionId, awaitNewSession)` | Compact persisted progress, indexed/candidate counts, phases, path, and failure/cancellation state. Back returns Home without canceling. Stop requires confirmation. Successful completion navigates only while this entry is resumed. |
| `PreviewRoute(groupId, fileId)` | Photos use an in-app preview with pinch zoom, double-tap zoom, filename, path, size, and date. Other categories show metadata and open a secure local system viewer when available. |
| `SettingsRoute` | Appearance (System, Light, Dark), scanning, review and deletion, file type, and About sections. Values come from DataStore, mounted volumes, the real storage permission state, and `BuildConfig.VERSION_NAME`. |
| `ScanLocationsRoute` | Selects entire shared volumes or specific folders and files using the in-app browser. Folder navigation and selection are separate actions. Done validates and saves the selection; Back leaves unsaved changes behind. Overlapping paths are collapsed, unavailable selections cannot be saved, and an empty scope cannot start a scan. |
| `TypesToScanRoute` | Four large rows for Photos, Videos, Audio, and Documents. At least one category must remain enabled. |
| `ExclusionBrowserRoute(kind)` | In-app browser for mounted shared volumes. It keeps navigation inside a selected volume, sorts folders first, returns canonical paths, excludes app-private and restricted Android locations, and reports duplicate, parent-covered, and inaccessible selections. |

Storage permission is a Home state and an Android system settings flow, not a fake in-app toggle. Deletion confirmation and results, scan stop confirmation, result clearing, and the add-exclusion chooser are dialogs or sheets. MediaStore deletion authorization is an Android system dialog.

## Design system

The default appearance is Dark. DataStore also supports Light and System. Both use the same layouts and semantic Material roles; dynamic color is disabled.

| Role | Dark | Light |
| --- | --- | --- |
| Background | `#252422` | `#FFFCF2` |
| Card | `#403D39` | `#F0EBE1` |
| Primary text | `#FFFCF2` | `#252422` |
| Secondary text | `#CCC5B9` | `#625E58` |
| Primary action | `#EB5E28` | `#EB5E28` |
| On primary | `#252422` | `#252422` |

Results uses a 36 sp size value with a separate 20 sp unit, then 22 sp statistics and 14 sp labels. Body text is 16 sp, secondary text 14 sp, and section titles 18 sp. Cards have 16 dp corners and zero tonal elevation. Compact screens use 16 dp horizontal padding; wide layouts are centered within 720 dp. Buttons and interactive targets retain their minimum touch sizes. Member thumbnails are 56 dp.

The summary scrolls with the member list; the deletion footer reserves its own space above system navigation. The selected count and size share a wrapping summary, and action buttons use the approved orange accent, including deletion confirmations. Each copy explicitly shows Will be kept or Selected for deletion; its checkbox changes that state. Delete actions include the selected file count, and confirmation uses the captured selection count and discloses hidden selections. Filtered summaries explicitly cover all results, empty messages identify the selected category or search, and the selected filter scrolls fully into view. Long names and locations use a single line with middle ellipsis to retain their ends; tapping a file opens its full name and path in the existing preview. At large font scale Home file types and Results statistics stack, and member metadata moves below the thumbnail/name row. Displayed sizes use decimal units and at most one decimal with `Locale.ENGLISH`. Storage progress represents actual used bytes / total bytes. Cards have no decorative side stripes, and interface text does not use round separator symbols.

### UI implementation details that matter in review

`ScreenColumn` uses `safeDrawingPadding()`, a maximum width of 720 dp, 16 dp horizontal padding below 600 dp and 32 dp at or above 600 dp. Results has its own centered 720 dp layout, 16 dp list padding, and safe drawing insets. Its footer is independently scrollable and capped at 45 percent of available height so a short viewport retains list space. Do not assume every screen uses `ScreenColumn`.

`LARGE_FONT_SCALE` is 1.5; the affected layouts switch at that threshold. The focused layout tests exercise 1.0 and 2.0, including 320 dp plus 2.0 together. Primary actions have a 56 dp minimum height; member checkboxes and header icon buttons use 48 dp targets. Member rows and headers are separate lazy-list items with stable keys, allowing long groups to scroll to the final member.

Preview and selection are separate hit targets. Preview semantics include the full filename and path; checkbox semantics include the filename and keep/delete state. Group and screen headings expose heading semantics. These are source-level accessibility affordances, not evidence of a completed TalkBack review. `StringResourcePolicyTest` checks values XML resources for em dash, en dash, middle dot, and bullet characters; it is not a general accessibility or copy-quality audit.

### Results semantics

| UI value or action | Current meaning |
| --- | --- |
| Files and group counts | Every member of every active duplicate group is counted, including keepers. These are not the number of extra copies or the number selected for deletion. |
| Available to free / group extra space | `availableBytes()` computes potential savings from readable, unchanged members while allowing a keeper. If there is a non-writable valid keeper, all writable valid copies can contribute; otherwise one eligible copy is subtracted. This is independent of the current selection. |
| Selected count and bytes | Eligible selected members across the entire active session, including groups hidden by filters or search. Stored group/session `reclaimableBytes` tracks selected eligible bytes; do not substitute it for potential savings. |
| Search and categories | Filtering is at group level. Search matches group title or any member filename/path without case sensitivity, then displays all members of a matching group. Category filtering uses the group's category. Byte-identical files can have different extensions or member categories. |
| Sorting | Potential savings, copy count, title, newest member timestamp, or oldest member timestamp; title and group ID provide tie-breaking. Default: largest potential savings. |
| Show all | Clears query and category filter. It does not clear persisted selections. Closing search also clears its query. |
| Recommended keeper | Determines initial auto-selection and member ordering; it is not a permanent prohibition on selecting that member when another valid keeper exists. |
| Delete confirmation | Captures session, member IDs, full affected groups, selected bytes, and hidden count. Queued selection changes finish first. Execution rejects a changed session or batch. A running scan or pending deletion journal blocks deletion. |
| Clear results | Deletes scan metadata, not files. The ViewModel refuses clearing while pending deletion recovery exists. |
| Deletion outcome | Freed bytes count only confirmed deletions. Every non-deleted status, including already vanished files, contributes to Not deleted. The result remains visible when deletion removes the last duplicate group. |

`SavedStateHandle` retains Results filter, sort, query, and search visibility. Persisted selections belong to Room. The pending confirmation batch and outcome dialog are transient ViewModel state; they are not a durable authorization to delete after process recreation.

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
| `ui.components`, `ui.home`, `ui.scan`, `ui.results`, `ui.exclusions`, `ui.settings`, `ui.preview`, `ui.permission`, `ui.model` | Shared UI components, UI category mapping, immutable UI state models, and Material 3 Compose screens. Review and selection are inline in Results; there is no `ui.review` package or separate review route. |

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
8. Room flows update Home, Results, Scanning, and totals.

## Persistence

Room database `duplicates.db` is schema version 5 with schema export enabled. `MIGRATION_1_2` adds the pending-deletion journal, `MIGRATION_2_3` adds the nullable scan failure reason, and `MIGRATION_3_4` removes hash/size uniqueness from duplicate groups and adds `recovery_files`. `MIGRATION_4_5` adds isolated tool sessions/items and cleanup operation/item journals, preserving the existing tables. No destructive fallback is configured. The database contains thirteen tables:

| Table | Purpose and important constraints |
| --- | --- |
| `scan_sessions` | Scan status, start and completion timestamps, active and canceled flags, counts, bytes, current phase and path, progress work, candidate groups, skips, errors, and an actionable failure reason. Active completed results and running or latest session are observable separately. |
| `indexed_files` | One discovered file per session and canonical path. Stores display and location metadata, category, size, last-modified time, MediaStore URI and states, quick and full hashes, access flags, and changed-since-scan state. Cascades with its session. |
| `duplicate_groups` | Byte-verified content group with a unique ID and a non-unique session/hash/size index. Distinct byte partitions may share cached hash and size metadata. Stores category, copy count, selected reclaimable bytes, and a title derived from a real member filename. Cascades with its session. |
| `duplicate_members` | Joins a group to an indexed file and persists recommendation, deletion selection, and auto-selection protection. Both foreign keys cascade. The group plus file pair is unique. |
| `pending_deletion_operations` | One journal header per in-progress deletion. Stores a generated operation ID and creation time. |
| `pending_deletion_items` | Snapshot of each selected member for an operation: group, member and indexed-file IDs, canonical path, size, last-modified time, and optional content URI. The operation plus member is the composite key. Operation and group IDs are indexed, and deleting the operation cascades to its items. |
| `fingerprint_cache` | Persistent quick and full SHA-256 values. Canonical path plus volume is the composite key; size and last-modified time are indexed identity metadata. It is independent of a scan session. |
| `exclusions` | Unique canonical file or folder path, display name, type, and creation time. It is independent of scan sessions. |
| `recovery_files` | Durable recovery ID, original path, app-owned payload path, creation time, and optional expiry. A null expiry retains an interrupted or unverified move indefinitely. Independent of scan sessions. |

| `tool_sessions` | Tool, run identity, frozen scope, timestamps, progress, completeness, measurements and active/latest status. Failed or canceled replacements preserve the previous result. |
| `tool_items` | Stable session/item identity, metadata, explicit selection, validity, optional similarity group/keeper/fingerprint and allocated size. |
| `cleanup_operations` | Ordinary-file or directory cleanup header linked to its retained tool session. |
| `cleanup_operation_items` | Frozen selected identity, own content digest, preallocated recovery ID, intended kind and per-item outcome. |
File contents and thumbnails are not stored in Room. Clearing scan results deletes scan sessions and their cascading indexed files, groups, and members. It retains recovery copies, exclusions, fingerprint cache entries, and DataStore preferences.

DataStore file `duplicates_settings` contains these preferences:

| Key | Default | Effect |
| --- | --- | --- |
| `theme_mode` | `DARK` | `SYSTEM`, `LIGHT`, or `DARK`; a missing or unrecognized value falls back to Dark |
| `scan_hidden_folders` | `false` | Skip any entry whose current name starts with a period |
| `ignore_system_folders` | `true` | Skip conservative technical directory names such as cache, tmp, temp, and lost.dir |
| `auto_select_duplicate_copies` | `true` | Select eligible non-recommended copies when results are created |
| `confirm_before_delete` | `true` | Show the in-app confirmation before the deletion coordinator runs |
| `scan_photos` | `true` | Include the Photos category |
| `scan_videos` | `true` | Include the Videos category |
| `scan_audio` | `true` | Include the Audio category |
| `scan_documents` | `true` | Include Documents and other ordinary user files |
| `selected_volume_ids` | `{ "primary" }` | Select the primary shared volume |
| `scan_selected_items_only` | `false` | Restrict traversal to explicitly selected folders and files |
| `selected_scan_paths` | Empty set | Canonical selected paths; a selected folder includes its descendants |

The scope editor enables Done only for a nonempty selection. The Settings ViewModel prevents the final enabled category from being disabled. Missing or restricted selected paths fail the scan without falling back to entire-storage scanning. Selected-path traversal retains hidden-folder, system-folder, file-type, and exclusion filters.

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

Discovery and scanner progress is written to the staging session and notification at most every 250 ms, except phase changes and completion. Finding files uses indeterminate total work. The scanner reports real item or byte totals, but `toOverallProgress()` in `app/ScanRunner.kt` maps them into fixed phase ranges on a 600,000-unit scale: discovery completion is 200,000, quick hashing ends at 300,000, full hashing at 400,000, and verification at 600,000. Displayed overall progress is therefore phase-weighted, not a percentage of all file bytes or a time estimate. The persisted UI phases are Finding files, Comparing candidates, and Verifying duplicates.

Starting a staging session does not deactivate the previous successful session. At launch, `ScanRunner` snapshots only preference-selected mounted volumes whose directories canonicalize to readable roots. A stale selected ID for a detached volume is ignored when another selected root is usable; no usable selected root returns `EMPTY_SCOPE`. Storage access and the exact captured root identities are checked after discovery and again before activation. Only a fully mapped and transactionally inserted staging result can become active. Completion marks it active and removes older scan sessions. Cancellation marks the staging row canceled in a non-cancellable cleanup block. Failure marks it failed with a persisted reason for removed permission, unavailable volume, full storage, database failure, foreground worker failure, or a generic failure. An orphan running session is failed before a new session starts. Canceled and failed staging sessions leave the previous active result available.

## Background scan execution

`DuplicateScanWorker` is a long-running `CoroutineWorker`. Immediately after the staging session is persisted, its session-start callback calls `setForeground()` before discovery begins. Later progress updates drive the same persisted phase and work measurements shown in the UI. The low-importance `duplicate_scans` notification contains app name, scan label, current phase, determinate or indeterminate progress, a tap target that opens Scanning, and a WorkManager cancel action.

The app's source manifest directly declares:

- `MANAGE_EXTERNAL_STORAGE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS`
- `PACKAGE_USAGE_STATS`, granted through Android Usage access only for Unused apps; MAIN/LAUNCHER package queries are scoped and `QUERY_ALL_PACKAGES` is absent

Dependencies contribute `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, and the app-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` to the merged manifest. This does not add a Duplicates boot scan or periodic request. The source manifest explicitly removes dependency-contributed `ACCESS_NETWORK_STATE` during manifest merge. The existing debug and release merged manifests inspected on 2026-11-02 contain none of `INTERNET`, granular media, camera, microphone, contacts, location, nearby-device, or account permissions. This was inspection of generated manifests, not a fresh APK packaging check.

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

The user starts deletion from inline Results:

1. The ViewModel reads the current persisted group and selected members.
2. The default in-app dialog summarizes selected file count and selected bytes. The `confirm_before_delete` preference can disable this app dialog, but the Results selection remains required and Android authorization still applies where required.
3. `DeletionCoordinator` snapshots every group member. A matching size and last-modified time accepts current membership. Changed metadata triggers full SHA-256 and byte comparison against another readable group member.
4. Missing, changed, unreadable, non-writable, or failed-revalidation selected files are kept or recorded as vanished. No selected file proceeds unless a readable matching unselected keeper exists.
5. Before mutation, `RoomDeletionResultStore` writes an operation and every selected group, member, indexed-file, path, identity, and content-URI snapshot to the pending-deletion journal.
6. `toDeletionGroup()` marks media for Android write authorization (`MediaStore.createWriteRequest`, chunks of up to 2,000). Approval does not delete files. The coordinator compares each candidate with a current unselected keeper again after approval. `RecoveryRepository` persists a Room recovery row, atomically moves the directory entry into a hidden app-specific Android/media recovery directory on the same volume, then verifies the staged bytes. No copy-and-delete fallback is allowed. Failed or interrupted verification leaves a non-expiring recovery copy. MediaStore refresh uses the original path, never destructive deletion through a cached URI. Each move is checkpointed.
7. The result distinguishes deleted, vanished, changed, revalidation failed, inaccessible, authorization canceled, direct failure, and blocked-no-keeper outcomes.
8. Normal completion uses one Room transaction to remove deleted or vanished files and members, remove changed members from duplicate groups, drop groups with fewer than two matching members, update surviving counts and selected reclaimable bytes, recalculate session totals, and delete the journal operation.

If the process ends during deletion, `MainActivity` requests reconciliation from an orphan Activity result or from `onResume` only when no live authorization continuation exists. `DeletionRecoveryGate` prevents the live callback and following resume from handling the same request twice. `PendingDeletionReconciler` serializes concurrent calls, reads every journaled path, and treats only `snapshot == null` as deleted. One Room transaction removes those matching members and indexed files, updates or drops affected groups, recalculates session totals, and removes the journal. Existing files are left in the saved results. If any journaled path is inaccessible or its volume is unavailable, the operation remains journaled for a later retry.

Deletion does not claim cross-file atomicity. The result dialog reports moved and failed counts and zero immediate bytes freed. Settings > Recovery copies restores a copy without overwriting an existing file; the payload is retained. Verified copies expire after 30 days and are removed when this screen is opened. Unverified copies never expire automatically. Recovery requires the original volume and is lost when app data is cleared or the app is uninstalled.

### Revalidation limits and review boundaries

The coordinator compares current bytes before each move, after media approval and after staging. Moving the selected directory entry preserves a last-moment replacement instead of unlinking it. Recovery provides a bounded 30-day window for verified copies; it is not an immutable backup or an atomic snapshot across external writers and multiple files.

`AndroidDeletionStorage.snapshot()` returns `null` for a missing path **and for a symbolic-link or non-regular entry**. Access-denied, security, and I/O errors instead produce an inaccessible snapshot. Both the coordinator and reconciler use this contract when interpreting deletion/absence; `null` is not exclusively proof that no filesystem entry exists. Reconciliation repairs saved metadata and does not retry deletion of remaining files.

## Exclusions

Exclusions store canonical paths. A candidate must exist, be readable, have the requested file or folder type, and remain under a currently mounted shared root. Exact duplicates are rejected. A parent folder exclusion covers descendants. Adding a broader folder removes redundant descendant exclusions. Removal updates the Room flow immediately.

The browser canonicalizes every opened path, prevents traversal outside the mounted root, ignores symbolic and non-regular entries, hides app-specific directories plus `Android/data`, `Android/obb`, and recycle folders, and lists only readable entries. File mode shows folders and files. Folder mode shows folders and exposes Select this folder.

## Preview behavior

Results uses a 56 dp `MediaThumbnail` component with the category icon as its fallback. Photos load through Coil from the saved content URI or canonical path. Videos use a thumbnail-size request with Coil's explicit `VideoFrameDecoder`, including for extensionless MediaStore URIs, and add a contrasting play icon over a Surface badge at 88 percent alpha. Audio artwork is requested only for currently visible lazy-list rows. `AudioAlbumArtLoader` converts the stored MediaStore Files URI to the volume-specific Audio Media URI, queries `ALBUM_ID`, builds the matching album URI, and calls `ContentResolver.loadThumbnail` on `Dispatchers.IO`. Missing rows, artwork, access, or provider support leave the Audio icon in place. Documents keep their category icon.

Photos are rendered with Coil from the MediaStore content URI or canonical path. Gesture scale is clamped from 1x through 5x; double tap toggles 1x and 2x. Preview is read-only.

Videos, audio, and documents use the generic metadata screen and a system `ACTION_VIEW` chooser. When a MediaStore content URI was stored, it is opened read-only to confirm that it is usable; a stale URI produces the missing state. Only a file with no stored content URI uses the readable canonical file through the non-exported `PreviewFileProvider`. It resolves the volume and relative path against currently mounted shared-storage volumes on every access, rejects paths outside the volume, Android/data, Android/obb, and the app's own Android/media directory, and permits only read operations. The viewer receives a `content://` URI through both intent data and `ClipData`, with temporary read permission. A specific stored MIME type is preferred; generic MIME values fall back to extension lookup and then `application/octet-stream`. Unsupported, missing, provider, and no-compatible-viewer cases remain non-destructive and produce an explanatory state. No file is copied for preview and no `file://` URI is exposed.

## Tests and runtime verification

### Test layers and coverage map

The local JUnit suite covers scanner, discovery, hashing, cache reuse and invalidation, Room transactions, staging and specific failure reasons, background worker outcomes, selection, deletion, MediaStore authorization and cleanup, interrupted-deletion reconciliation, preview URI preparation, media thumbnails and album-art loading, permissions, preferences, ViewModels, formatters, string policy, and Robolectric Compose screens. Compose coverage includes scan-start and View scan actions, stop and delete confirmation, the exact preview callback target, deletion-result counts and freed space, empty and completed Home states, Results interactions, exclusions, settings, and 200 percent font scale. Fakes and temporary directories isolate file-system and deletion tests from personal files.

The string resource policy test rejects U+2014, U+2013, U+00B7, and U+2022. Room schema tests cover relationships, activation, selection guards, deletion updates, pending-operation journaling and reconciliation, and clear behavior. Scanner tests cover same and different content, quick-hash collisions, final byte comparison, file changes and removal, cancellation, cache behavior, and deterministic grouping. Deletion tests cover direct and authorized deletion, partial failure, changed or vanished files, canceled authorization, keeper enforcement, checkpointing, process interruption, reconciliation serialization and gating, and transactional totals.

`app/src/test/resources/robolectric.properties` selects **SDK 35 by default**; `AudioAlbumArtLoaderTest` explicitly overrides it to SDK 30. The current Android-resource and Compose JVM tests do not run on the project's target API 37. `MainDispatcherRule` supports coroutine/ViewModel tests; `core/database/ScanStoreDatabaseTest.kt` provides shared database test setup rather than an additional test suite. Coverage reporting uses JaCoCo 0.8.16, includes application classes loaded by Robolectric without source locations, and is consumed by the root Sonar task. No coverage percentage or remote quality-gate status was established in this documentation update.

| Existing focused coverage | What the source actually exercises | Boundary |
| --- | --- | --- |
| `ReadyHomeScreenTest` | 320 x 800 dp and 411 x 900 dp; Light/Dark and 100/200 percent fonts; settings, location, category, previous-result and scan actions | Fixture state and callbacks, not a real storage scan |
| `ResultsScreenTest` | Eight test methods; both portrait sizes and both themes/font scales; 640 x 320 dp at 200 percent; keep/delete states, last-copy disabled state, exact preview target, global confirmation, hidden selections, empty filters/search, selected-chip visibility, outcome reasons and final-row/footer separation | Robolectric native graphics and Compose semantics; no Android MediaStore dialog or TalkBack session |
| `SettingsLayoutTest`, `ScanSelectionScreenTest` | Both portrait sizes, both themes/font scales; settings reachability, separate folder navigation/selection, disabled empty Done and enabled selected Done | Does not establish every detail screen or every screen state at these sizes |
| `PresentationScreensTest`, `SettingsDetailScreensTest`, `PermissionScreenTest` | Scanning and stop callbacks, a scan-phase row at 200 percent, exclusions, settings, file types, browser callbacks, unavailable generic preview, storage-access explanation | Not a full 320 dp x 200 percent matrix for all these screens |
| `ResultsDeletionTest` and domain/data deletion tests | Captured batches, hidden selection, queued uncheck, changed selection/session rejection, cancellation, partial failure, keepers, checkpoints, and recovery | Fakes, local files and local Room; not proof of provider/OEM behavior |
| `DuplicatesDatabaseMigrationTest` in `androidTest` | Existing 2-to-3 and 3-to-4 checks plus full 1-to-5 and populated 4-to-5 upgrades preserve prior rows and validate the additive schema | API 36 evidence is in the expansion report; not OEM database or physical-storage coverage |

For future UI changes, choose the affected screen/states and explicitly include **320 dp, 200 percent font scale, and their combination**, plus both supported themes. Use the existing 411 dp reference and short landscape checks where relevant. Check reachability, clipping, scrolling, selected-state semantics, and system-inset/footer overlap. Record which checks actually ran; the matrix above does not establish complete app-wide accessibility coverage.

### Verification commands and prerequisites

Run from the repository root with the Android SDK and configured Java environment available. Choose the tasks required by the change; these commands are not a claim that they ran during this documentation update.

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.ui.results.ResultsScreenTest"
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:ktlintCheck :app:detekt
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:lintRelease :app:assembleRelease
.\gradlew.bat :app:createDebugUnitTestCoverageReport
.\gradlew.bat connectedDebugAndroidTest
```

`config/android-check.json` declares debug build, debug local tests, connected debug tests, debug lint, ktlint, Detekt, and stability tasks, with 20-minute phase timeouts. Its full lint list adds `:app:lintRelease`. Shared-check wrappers such as `tools/tc.ps1` require PowerShell 7 and forward to the external `Android-check/tools/InvokeProjectCheck.ps1`, located through `ANDROID_CHECK_HOME` or the sibling `../Android-check`. These wrappers are not self-contained implementations; see `README.md` for their documented flags. A missing installation is an error. Their external implementation was not re-audited here.

Compose Rules are registered with ktlint and Detekt; Google's Android security lint is registered with ordinary Android Lint. `:app:stabilityCheck` compares against `app/stability/` and depends on both debug and release Kotlin compilations. `:app:stabilityDump` writes baselines; it is not a substitute for passing a check. Root `sonar` depends on debug assembly and the debug unit-test coverage report and uploads analysis; it is not needed merely to edit this document. No scanners beyond the required pre-read secret scans were invoked for this update.

### Current instrumentation source and execution cautions

The current Android test source defines **five test methods** across three classes:

- `MainActivityTest.settingsAppearanceAndBackAreUsable`: opens Settings, switches to Light, goes Back, and sets the preference to Dark afterward.
- `MainActivityTest.fixtureOnlyScanOpensResultsPreservesPreviewAndDeletesOnlyReviewedCopies`: requires an empty test installation (`latestSession == null`, otherwise skipped), creates `/sdcard/DuplicatesUiRedesignFixture` only if absent, prepares two PNG and two text copies, selects that scope, scans, opens preview, captures theme/layout evidence, and attempts deletion of the reviewed copies. It grants storage/notification access, changes display/font settings, and can approve the system MediaProvider deletion dialog. Its cleanup attempts to restore captured display/preferences, stop owned scan work, clear test scan metadata, and remove its fixture directory. It does not restore the permission grants, and evidence screenshots remain in the app's external-files `ui-refinement-evidence` directory.
- `DuplicatesDatabaseMigrationTest.migrationFrom2To3AddsNullableFailureReasonWithoutChangingExistingSessions`: validates schema 2 to 3.
- `DuplicatesDatabaseMigrationTest.migrationFrom3To4AddsRecoveryAndAllowsIndependentBytePartitions`: validates schema 3 to 4, including the recovery table and non-unique duplicate-group index.
- `RecoveryStorageTest.sharedStorageFileCanBeStagedAndRestoredOnItsOriginalVolume`: stages a synthetic file on shared storage, restores it to its original location, and verifies that its recovery payload remains available. Cleanup removes the fixture and its recovery payload.

Use a disposable, isolated test device/emulator installation for the full connected suite. This suite mutates app and device state; its fixture deletion is not a read-only smoke test. The previous two-test connected run described below is not a pass of these five current methods.

The fixture test selects the confirmation button using `R.plurals.delete_selected` and `hasAnyAncestor(isDialog())`, matching the current UI. This source correction does not establish a passing device run or resolve the earlier image-deletion result.

### Evidence inspected on 2026-11-02

| Evidence present locally | Observed result | What this establishes |
| --- | --- | --- |
| `app/build/test-results/testDebugUnitTest/TEST-*.xml` | 45 suites, 223 tests, 0 failures, 0 errors, 0 skipped; suite start timestamps 2026-11-02 01:06:07 to 01:06:58 UTC | A prior local run reported these results. It was not rerun, and the XML does not identify an exact source commit/working-tree hash. |
| `TEST-com.emma.duplicates.ui.results.ResultsScreenTest.xml` in that directory | 8 tests, no failures, errors, or skips | Results tests are included in that saved result set. |
| `app/build/reports/lint-results-debug.txt` | `No issues found.` | Existing debug lint report; not a new lint run or release lint result. |
| `app/build/outputs/results-clarity/` | `results-dark-320-font200.png`, `results-dark-411.png`, `results-light-411-font100.png` exist | Existing Robolectric screenshots, written by `ResultsScreenTest`; not device screenshots or screenshot golden assertions. Their visual contents were not re-reviewed in this update. |
| `app/build/outputs/ui-refinement/` | Existing refinement screenshots, including `deletion-result.png` | Historical image artifacts, not proof of a complete passing test run. |

No current connected-test report was found at the normal `app/build/outputs/androidTest-results` or `app/build/reports/androidTests` locations during this review. Files under `app/build/` and `reports/` are local generated evidence, not durable guarantees that another checkout will contain the same artifacts. Preserve the command, variant, source revision/dirty state, device/API, and pass/fail/skip counts when recording a new run.

### Earlier verification notes retained from the previous document

The following is historical recorded context, **not re-executed or independently re-established here**. In particular, older test names and results describe the source at that time, not the current instrumentation suite.

The pre-redesign document recorded two passing connected tests on `Duplicates_Pixel9_API37(AVD) - 17`: the then-current `MainActivityTest` checked launch, the storage permission explanation, Results navigation, the empty-results state, and return to Home; `DuplicatesDatabaseMigrationTest` checked Room 2 to 3.

Manual runtime verification on the same Pixel 9 API 37 Android 17 AVD used only isolated `DuplicatesQa-20260812` fixture directories under `/sdcard/DCIM`, `/sdcard/Pictures`, and `/sdcard/Movies`. A real scan indexed 9 files into 3 exact groups. Photo preview opened, Android authorization was approved for one 1.2 MB duplicate, the file disappeared, and Room-backed results and totals updated. Notification denial still allowed the scan to continue in the background. Notification allowance showed the ongoing notification, its tap opened Scanning, and Stop canceled work. A canceled scan preserved earlier completed results. The UI was also checked at 200 percent font scale and in landscape, and exclusion add and remove were exercised. All three fixture directories were removed afterward and their absence was verified.

Scanner and deletion edge cases are additionally validated with isolated local tests rather than unrelated device files.

The interrupted-deletion journal and reconciliation paths are covered by local tests. The production database builder registers `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4` and `MIGRATION_4_5`; exported schemas 1 through 5 are present, and the earlier notes report a passing `MigrationTestHelper` 2 to 3 run on Android 17. The evidence recorded here contains no device run forcing an operating-system process kill during live MediaStore authorization.

The 2026-11-01 scan-selection and UI refinement passed 217 local tests with no failures or skips, Android Lint, Detekt, and ktlint for the touched Kotlin files. Layout tests cover light and dark themes at 320 dp and 411 dp with 100 and 200 percent font scales. The debug and instrumentation APKs built successfully.

On the isolated API 36 emulator, selecting a folder through Settings, saving the scope, scanning four fixture files into two groups, opening a preview, and switching themes worked. The complete fixture-deletion test did not pass: the text duplicate was deleted, but the image duplicate remained and the app reported one file not deleted. The cause is unresolved; this is not recorded as a passing deletion check. Emulator system-app ANR dialogs affected earlier attempts. No physical-device run was performed for this refinement. Test fixtures were removed and the owned read-only emulator was stopped. Visual evidence is in `app/build/outputs/ui-refinement`.

The 2026-11-02 results clarity change passed all 223 local tests with no failures or skips (`:app:testDebugUnitTest`), `:app:assembleDebug`, and `:app:lintDebug` with zero issues. After test-helper restructuring, all eight `ResultsScreenTest` tests passed again. They cover visible keep/delete states and checkbox callbacks, the last-copy disabled state, deletion counts and confirmation snapshot wording, hidden selections, category/search empty states, selected-filter visibility, and layouts at 320/411 dp with 100/200 percent fonts in both themes, plus short landscape. Detekt passed with zero findings using the already-installed JDK 21; JDK 25 could not run the existing Detekt version. Ktlint passed for all seven changed Kotlin files using `internalKtlintGitFilter`. Three reviewed Robolectric screenshots are retained in `app/build/outputs/results-clarity`. No device installation, physical-device or TalkBack run was performed for this change, and no personal files were deleted.
The debug APK output is `app/build/outputs/apk/debug/app-debug.apk`.

### Open verification limits

- The current five-test instrumentation suite has no passing connected-run evidence established here.
- The earlier API 36 image-duplicate deletion did not pass and its cause remains unresolved in the recorded evidence. A successful older API 37 deletion does not resolve that failure.
- No current physical-device or TalkBack verification is recorded for the Results clarity change. Robolectric tests at SDK 35 do not prove API 30/36/37 runtime behavior or full-screen accessibility coverage.
- A real process kill during a live Android deletion authorization and migration paths 1 to 2 / 1 to 3 are not established by the existing connected test source/results described here.
- Existing local test/lint reports do not certify a signed release, R8 runtime behavior, remote security/quality gates, or publication readiness. The deletion revalidation and snapshot-contract limits described above remain part of the implementation's actual behavior.
