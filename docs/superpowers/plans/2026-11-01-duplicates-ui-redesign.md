# Duplicates Android UI and Scan-to-Results Redesign Implementation Plan

> **Status: historical planning record, superseded in part.** The redesign was implemented in commit `a9d4779` on `codex/ui-redesign`. The unchecked task lists preserve the original plan; they are not a current backlog or evidence of unfinished work. Current behavior and verification limits are documented in [PROJECT.md](../../../PROJECT.md) and the implementation. Follow current user instructions rather than executing this historical plan again.

**Goal:** Replace the oversized, disconnected interface with a coherent mobile interface that opens results automatically after scanning, displays duplicate copies together, supports selection and deletion directly in results, and implements the approved visual direction and revised results summary.

**Architecture:** Keep Kotlin, Jetpack Compose, Material 3, the existing Navigation Compose stack, ViewModels, Room, DataStore, WorkManager, Coil, and the existing exact-duplicate and deletion services. Change presentation, navigation orchestration, and the state needed for inline multi-group review. Keep Room as the source of truth for file membership and selection; do not introduce a parallel selection database or a new application architecture.

**Tech Stack:** Inspected configuration: Kotlin 2.4.21, AGP 9.5.0, Gradle 9.8.2, JDK 17, Compose BOM 2026.09.00, Navigation Compose 2.10.2, Room 2.8.5, WorkManager 2.12.0, Coil 3.6.3, minimum SDK 30, compile/target SDK 37. These are recorded repository values, not recommendations to upgrade.

**Original spec:** Sections 1-5 record the original design contract. Later implementation changes are noted below; the referenced design images were conversation attachments and are not repository assets.

**Planning date:** 2026-11-01, Europe/Helsinki.

**Repository:** [duplicates-android](../../../README.md).

**Planning baseline (historical, not current HEAD):** `da86d8d0c805a6d0dacbb3e2df84f0f9c2e632f4` on `codex/add-lint-check`; remote default branch is `main`. The existing untracked `hs_err_pid8932.log` predates this planning task and must be preserved.

## Global constraints

- Implement the requested redesign and its necessary behavior changes. Do not expand it into an engine rewrite, storage-permission migration, dependency upgrade, navigation-library migration, DI migration, or general repository cleanup.
- Inspect branch/status and current project instructions again before implementation. Never implement on `main`, `master`, or another protected integration branch; use a `codex/` feature branch when a new branch is needed.
- Preserve existing uncommitted work, stored results, preferences, exclusions, and user files. No destructive Git cleanup, app-data clearing, or uninstall/reinstall to make tests easier.
- Before reading a workspace file, run `sonar analyze secrets <path>`. If a secret is reported, do not read it; follow the project's secrets-on-read instructions. Do not print credentials.
- Source identifiers, filenames, comments, and this plan are English. User-facing implementation reports should be Finnish unless requested otherwise.
- Keep UI strings in Android resources. Preserve the existing English product language; translation is outside this task.
- No bullet or middle-dot separators between words or values in UI copy. Preserve the existing prohibition on en/em dashes in string resources. Ordinary decimal points, filename extensions, and functional overflow icons are not word separators.
- No decorative colored vertical stripe on a card's left side. No gradients, glows, unnecessary motion, or new decorative panels.
- Do not display `Find duplicate files` or `Keep what matters. Remove extra copies.` on Home. Do not replace them with another promotional heading.
- Keep at least one valid copy in every group. Never infer an original file from a filename, path, timestamp, or the mockup's ordering.
- Scanning never deletes files. Deletion always starts from an explicit user action and retains the existing confirmation preference, Android authorization, revalidation, checkpoint, and recovery behavior.
- Accessibility validation must explicitly cover 320 dp width, 200% font scale, and their combination. Do not infer coverage from a generic responsive test.
- All mockup sizes, filenames, counts, and images are illustrative. Production screens must use actual stored results, actual storage statistics, and actual file previews.
- The original planning task produced only this document. Later implementation and verification are recorded in PROJECT.md; this historical record does not define authorization for later tasks.

## Review focus

Each of these conditions has concrete tests assigned below:

1. An old completed session is still the latest database row when a new scan has only been enqueued: never open the old results as if the new scan finished. Tasks 3 and 10.
2. Search/filtering hides selected copies: never silently change the deletion scope or omit hidden selections from the user-visible explanation. Tasks 6 and 7.
3. A keeper becomes unavailable, a file changes, or Android authorizes only part of a batch: preserve a valid copy and report actual outcomes. Task 7.
4. A long filename/path at 320 dp and 200% font scale competes with a checkbox, preview, or bottom action: controls remain reachable and text remains understandable. Tasks 2, 4, 5, 8, and 10.
5. Back, recreation, or notification entry reopens a completed scan or loses selection/list position: navigation remains finite and state is restored from the proper owner. Tasks 3, 6, 7, and 10.

## 1. Accepted visual references and precedence

### 1.1 Approved dark theme and overall layout

The user explicitly approved the dark Home/Results concept with these roles:

| Role | Exact color | Use |
| --- | --- | --- |
| Background | `#252422` | Screen background, app bar, system-bar backdrop, gaps, bottom action area |
| Card/container | `#403D39` | Storage card, location row, file-type tiles, previous-scan row, duplicate groups |
| Primary accent | `#EB5E28` | Primary buttons, checked controls, selected filter, progress fill |
| Main text | `#FFFCF2` | Text and meaningful icons on dark backgrounds and cards |
| Supporting text | `#CCC5B9` | Metadata and secondary labels, not large surface fills |
| On primary | `#252422` | Text and check marks on orange fills |

Approved overview: the dark Home/Results concept attached to the original design conversation. The image is not included in this repository; its palette and layout are recorded here.

### 1.2 Approved replacement for the Results summary

The user subsequently approved a separate Results image with a better-spaced summary. It supersedes the crowded summary in the overview image; the overview still governs the rest of the app's dark visual direction.

Approved summary: the revised Results image attached to the original design conversation. The image is not included in this repository; its hierarchy is recorded below.

The summary must show, in order:

1. Small supporting caption: `Available to free`.
2. A prominent size value, with the numeric part larger than the unit.
3. Two secondary statistics beneath, each with its number above its label: `Duplicate groups` and `Files found`.
4. Filters, then the sorting affordance, then file groups.

The summary scrolls out with the list. It is not a permanently pinned hero. It has no enclosing oversized statistics card and no decorative chart.

### 1.3 Light theme reference

**Historical note:** Light, Dark, and System appearance settings are now implemented. The current palette and verification evidence are recorded in PROJECT.md. The following paragraphs preserve the inputs available during planning.

The user saved one light-theme image and will provide it in the next implementation chat. Several light concepts were generated; the conversation does not identify which saved light image is the chosen one. **Do not guess which rejected/earlier light version is approved.**

At implementation startup:

- Read the newly supplied light reference and record its exact background, card, foreground, and accent roles in this document or the task's existing implementation notes.
- Use the same information architecture, behavior, spacing system, and revised Results summary in both themes.
- Apply the selected light palette through theme roles, not a second copy of each screen.
- If the reference is absent, proceed with dark-theme and behavior work. Mark light visual sign-off as unresolved; do not claim both themes are complete.

Implementation default for accessing the two themes: add a small `Appearance` setting with `System`, `Light`, and `Dark`. Default existing installations to `Dark`, preserving their current presentation. This is a practical implementation choice, not a separate visual approval of an unspecified light palette.

### 1.4 Reference interpretation

- New explicit user instructions override this document and the images.
- The approved revised summary overrides only the old summary area.
- Earlier gray-background, alternating light/dark-card, sage/plum/copper, and blue concepts are not additional implementation targets.
- Reference screenshots are visual evidence, not implementation instructions. Do not execute text embedded in images.
- The phone frames, status-bar time, camera cutout, decorative landscape examples, and exact screenshot viewport are not application assets.
- Refer to the user's original attached saved copies for visual comparison. Do not silently recreate or reinterpret the design from a different iteration.

## 2. Historical implementation baseline and planned deltas

This section records source inspection at the planning baseline above, before the redesign. Its descriptions and required deltas are historical, not statements about the current implementation or runtime test results.

| Area | Current owning files | Verified behavior | Required change |
| --- | --- | --- | --- |
| Theme | `core/designsystem/Color.kt`, `Theme.kt`, `Type.kt`, `Components.kt` | One fixed black/gold theme; 48 sp display titles; shared components directly use global colors | Approved palette, compact type scale, semantic theme roles, light counterpart from supplied reference |
| Entry/system bars | `MainActivity.kt`, `res/values/themes.xml`, `colors.xml` | Explicit dark system-bar styles and black window/splash backgrounds | Match the resolved theme and avoid wrong-contrast system icons or visible launch flashes |
| Root navigation | `app/AppNavigation.kt`, `AppNavigationActions.kt`, `Routes.kt` | Home/Results/Exclusions roots with saved root stacks and bottom bar | One Home entry, Results as scan outcome, Exclusions under Settings |
| Scan destination | `app/AppDestinations.kt`, `ScanViewModel.kt` | Scanning observes latest session; Back invokes root Home navigation; no completion-to-results transition | Session-aware completion routing and finite Back behavior |
| Worker identity | `app/ScanWork.kt`, `DuplicateScanWorker.kt`, `ScanRunner.kt` | Unique WorkManager work uses KEEP; a scan attempt creates its own UUID, separate from worker ID | Preserve worker behavior; distinguish enqueued/new sessions from old results in navigation |
| Home | `app/HomeViewModel.kt`, `ui/home/HomeScreen.kt`, `ReadyHomeScreen.kt`, `StorageSummaryCard.kt` | Separate permission/ready/active/completed/empty presentations; storage metrics only mapped for completed Home | Consistent scan-configuration Home with real storage info and a previous-results entry |
| Results | `app/ResultsViewModel.kt`, `ui/results/ResultsScreen.kt`, `DuplicateGroupCard.kt` | One representative file per group, long parent paths, separate Review action | All member rows inline with per-file preview and selection |
| Selection | `data/ScanStore.kt`, `core/database/Daos.kt`, `domain/selection/DuplicateSelectionPolicy.kt` | Selection persisted in Room; DAO transaction prevents selecting the final valid unselected member | Reuse this boundary, surface rejected changes and preserve keeper behavior |
| Existing Review | `app/ReviewViewModel.kt`, `ui/review/ReviewDuplicatesScreen.kt` | Maps per-file state and constructs a one-group deletion request | Move required review behavior into Results; retire the redundant route once callers/tests migrate |
| Deletion | `domain/deletion/DeletionCoordinator.kt`, `DeletionModels.kt`, `data/RoomDeletionResultStore.kt` | Coordinator already accepts `List<DeletionGroup>` and supports revalidation, OS authorization, checkpoints and recovery | Wire multi-group Results deletion into the existing coordinator |
| Preview | `app/PreviewViewModel.kt`, `ui/preview/PreviewScreens.kt` | File-specific lookup and unavailable states; photos internal, other types use existing viewer support | Preserve file identity and return to the same Results state |
| Preferences | `data/preferences/PreferencesRepository.kt`, `app/SettingsViewModel.kt` | Existing scan scope, automatic selection, confirmation, category and volume settings | Reuse setters and final-category/volume guards; add only appearance persistence |
| Tests | `app/src/test`, `app/src/androidTest` | Existing Robolectric/Compose, Room, selection, deletion, navigation and worker tests | Extend behavior tests and replace obsolete root-tab/Review expectations |

Paths in the table are relative to `app/src/main/java/com/emma/duplicates/` unless a resource/test path is shown.

Two particularly important source facts:

- `ScanViewModel` currently derives display state from `scanStore.latestSession`. A simple `if (completed) navigateToResults()` would be unsafe when starting another scan because that flow can initially contain the previous completed session.
- Persisted group/session `reclaimableBytes` values are selection-dependent. `ScanResultMapper` starts from `selectedReclaimableBytes`, and `Daos.kt` recalculates them when selection changes. They cannot be reused unconditionally for the redesigned **potential** `Available to free` metric.

## 3. Visual and content contract

### 3.1 Shared layout and typography

Use these as implementation starting values at 100% font scale. Content-driven growth and accessibility reflow take priority over matching a screenshot's pixel dimensions.

| Element | Target |
| --- | --- |
| Compact phone horizontal inset | 16 dp |
| Content gaps | 8 dp within related details, 12-16 dp between cards, 20-24 dp between major sections |
| Card padding | 16 dp; member-row padding may use 12 dp inside the group |
| Card corner radius | 16 dp, consistently applied |
| Compact app bar | Standard platform/Material sizing; title approximately 22 sp |
| Section/group title | 16-18 sp, medium/semibold |
| File name/body | 16 sp; filenames need not be heavily bold |
| Supporting metadata | 14 sp with about 20 sp line height |
| Summary main number | 36 sp with about 44 sp line height |
| Summary unit | 20 sp, aligned with number baseline when space permits |
| Summary secondary numbers | 22 sp; their labels 14 sp |
| Primary action | Minimum 56 dp height, allowed to grow for wrapping |
| Interactive targets | At least 48 x 48 dp, including checkboxes and preview triggers |
| File thumbnails | Approximately 56 dp, consistent square crops and modest rounded corners |

- Use semantic Material color roles in composables. Do not import raw `Primary`, `Surface`, or `Background` constants into leaf screens to bypass the selected theme.
- Keep the ordinary card surface flat. Do not use elevation tint that unexpectedly changes the approved card color.
- The whole file group is one visually continuous card. Do not alternate white, orange, and dark backgrounds between its members.
- Selection is shown by an orange square checkbox and semantics; do not depend on color alone.
- No animated success spectacle, pulse, glow, or artificial scan delay. Standard restrained platform feedback is sufficient.
- Respect system font scaling. Never clamp `fontScale`, shrink text to force a screenshot layout, or put critical labels in a fixed-height clipping container.
- At large widths, constrain reading width and center content using existing layout primitives. Do not add a tablet redesign or new navigation pattern in this task.

Computed contrast checks for the approved opaque dark color pairs: `#FFFCF2` on `#403D39` about 10.52:1; `#CCC5B9` on `#403D39` about 6.30:1; `#252422` on `#EB5E28` about 4.55:1. The last pair has little margin: do not reduce text opacity on enabled orange buttons. Orange text on the card is only about 3.17:1; use the approved light text for ordinary small labels instead. Verify actual rendered states, including disabled controls and overlays, during implementation.

### 3.2 Home

Order, from top to bottom:

1. Compact app bar: app identity/title and Settings action.
2. Storage summary card: `Device storage`, real used/total values, a thin horizontal usage indicator.
3. `Scan location` heading and tappable location row opening Scan locations, with whole-storage or selected folders/files scope.
4. `File types` heading and four selectable controls: Photos, Videos, Audio, Documents.
5. `Previous scan` row only when saved completed results exist; show real group count and potential available space on separate lines.
6. Primary `Scan files` action, or a compact running-scan presentation while work is active.
7. Small `Files stay on your device.` footer where useful and factually retained.

Behavior:

- File-type choices immediately use the existing preferences and existing `SettingsViewModel.setCategoryEnabled(category, enabled)` guard. Do not allow zero selected categories through this new entry point.
- Keep the four current UI categories. Do not narrow Documents to PDF/text only or drop existing supported file extensions.
- **Implemented scope update:** The location row opens `ScanSelectionScreen`. Users can select whole storage volumes or specific folders and files. Folder navigation and selection are separate actions; Done validates and saves the scope. Unavailable selected paths never fall back to a whole-device scan.
- Use actual selected mounted volumes for capacity totals. For multiple volumes, label the scope accurately, such as `Selected storage`, rather than implying a single internal device capacity.
- If capacity cannot be read, show an unavailable state; do not display a fabricated zero-usage bar or the mockup's numbers.
- Before permission, show a compact explanation and access action in the same visual system. Do not initiate a scan without access.
- If a scan is active, show its phase/progress and `View scan`; prevent starting a second independent scan. Scope changes are disabled during the active attempt or clearly apply only to the next scan; use disabled controls for this implementation.
- Back from Results returns to this Home and does not discard results. The previous-scan row restores access to them without another scan.
- A previous scan with no duplicate groups still has a meaningful saved outcome; do not remove its entry just because available space is zero.
- Use a scrollable layout when height is insufficient. At ordinary phone sizes, arrange content as in the approved overview; do not spread small sections with enormous artificial spacers.

### 3.3 Scanning

- Compact app bar and readable current phase.
- Thin horizontal determinate progress only when a total is known. Otherwise use indeterminate progress; do not manufacture an exact percentage.
- Show real checked/indexed count. Keep candidates distinct from confirmed duplicate groups; the existing state already distinguishes these.
- Small phase indicators may show finding, comparing, and verifying, with text labels.
- Retain explicit `Stop scan` and its existing stop confirmation. Back leaves the screen while WorkManager continues; it is not an implicit cancel.
- Home shows ongoing progress after leaving Scanning. Notification entry returns to the relevant scan state.
- On successful completion and persisted results availability, open Results automatically. Do not leave a `Scan complete` page with a large `Back` action.
- No intermediate full-screen congratulations, completion timer, or extra `View results` tap.
- A failed/canceled scan is not successful. Explain the state and provide relevant retry/previous-results actions. Preserve the last completed results.

### 3.4 Results summary: final approved hierarchy

The summary is a normal item at the start of the Results `LazyColumn`:

```text
Available to free
189.5 MB

52                       108
Duplicate groups         Files found

[All] [Photos] [Videos] [Audio] [Documents]
Largest savings

[First group and its file rows]
```

- The caption sits above the amount, not beside it in a crowded sentence.
- The numeric part and unit have different text sizes, but form one accessible announcement.
- The two secondary statistics have separate labels. A subtle short divider between them is optional; no colored side stripe.
- At ordinary font scale, aim for approximately 150-170 dp for the summary itself, not including filters. This is a guide, never a fixed height.
- At 320 dp or 200% font scale, stack the statistics or allow labels to wrap when needed. A one-column summary is preferable to clipped labels.
- The summary scrolls away. Keep the compact app bar and necessary deletion action available; do not pin this whole summary above the file list.
- The image's `108` counts **all members of duplicate groups, including kept copies**, not all indexed files and not the selected-for-deletion count. In this example 108 files in 52 groups can produce 56 redundant copies. Do not relabel 108 as “files selected”.
- Put the statistic in the duplicate-results context and expose an accessible description equivalent to `108 files in duplicate groups` so `Files found` cannot be confused with all scanned files.
- Global summary values refer to the current completed result set, even when a filter/search is active. If a subset is shown, make that clear with a small result count near the list rather than silently redefining the headline.
- Do not hardcode MB. Reuse the existing decimal storage convention and resource formatting for bytes, kB, MB, GB, and TB. Add structured size parts only to support different value/unit styling without parsing a localized formatted sentence.

### 3.5 Results groups and file rows

- Show actual copies together, with a short category-aware group label such as `2 identical videos` and the group's potential extra space.
- Every member has its own actual preview model and file ID. Do not reuse one representative file ID for every thumbnail.
- Every row displays filename, size, and a concise distinguishing location. Photos/videos use existing thumbnail support; audio keeps existing lazy artwork behavior; documents retain a file-type fallback.
- Default to the recommended keeper first, then stable path/ID order. Toggling selection must not reorder rows.
- The trailing control reads `Keep` when unselected and shows a checked square when selected. It must be operable and announce selected/kept state, not merely be a decorative badge.
- Treat `Keep` as an unselected state, not a claim of originality. To change the kept copy, unselect another valid copy first, then select the former keeper. Reject selecting the last valid kept copy and explain why.
- Keep preview activation separate from selection activation. Tapping a thumbnail/name opens that exact file's preview; tapping the trailing selection control changes selection only.
- Full filename, full canonical location, size, date, and unavailable states remain accessible in the existing preview/details presentation.
- A list filename may use middle ellipsis while retaining its extension. Never truncate the only available destructive-action consequence or accessibility label.
- Shorten paths for presentation only. Never feed a shortened display path into preview, selection, permission, hashing, or deletion operations.
- Preserve distinguishing suffixes such as `Sent` and `Private`; include enough parents or a volume label when two displayed locations would otherwise be identical.
- A long group must not force thousands of thumbnail rows to compose at once. Use one vertically scrolling list with lazy group headers and member rows, styled to look like a continuous card. Avoid nested vertical scrolling and unbounded member Columns.

### 3.6 Results controls and bottom action

- Keep All, Photos, Videos, Audio, and Documents filters. Documents may be off the initial horizontal viewport, but must remain discoverable and usable.
- Default sorting is largest potential savings first. Keep all current sort choices unless a specific existing option is invalidated by the new data definition.
- Search continues to match filenames and full locations, even though list paths are shortened.
- Show a pinned, inset-aware action area with selected count and `Delete selected (<formatted size>)`.
- With nothing selected, show a clear zero-selection state and disable deletion. Keep copy-selection controls reachable.
- The bottom action has a minimum height, not a fixed height. At 200% font scale, allow the count and action label to wrap/grow and reserve their measured height below the list.
- No Home/Results/Exclusions bottom navigation bar.

### 3.7 Secondary screens

- Settings uses compact readable rows grouped by purpose, retaining scanning, automatic selection, confirmation, storage permission and app-version information.
- Exclusions is reached through Settings. Preserve add folder/file, browse, remove, and empty/error states.
- Settings subpages, exclusions, permission screens, previews, menus, sheets, and dialogs use the same typography and resolved theme.
- Permission text still explains local processing and that scanning does not delete files, without a giant multi-paragraph card.
- Preserve existing preview security and external-viewer behavior. Do not add voice control, advertisements, accounts, cloud upload, or new media similarity modes.

## 4. Data, selection, and deletion contracts

### 4.1 Separate potential savings from selected bytes

Introduce an explicit UI-level `availableBytes` value and keep `selectedBytes` separate.

- `availableBytes`: potential space that could be released from currently valid duplicate members while retaining a valid copy. It does not change simply because a checkbox changes.
- `selectedBytes`: sum of selected, deletion-eligible files. It changes with selection and governs the bottom action and confirmation.
- `duplicateGroupCount`: number of remaining duplicate groups in the active result snapshot.
- `duplicateFileCount`: number of members in those groups, including kept copies.
- `scannedFileCount`: indexed/scanned count, used for scan progress and truthful empty-state distinctions, not the `Files found` summary statistic.

Do not change Room schema or reinterpret existing persisted `reclaimableBytes` columns as part of this UI task. Derive the new potential values from group members and reuse them consistently in Results, group headings, Home previous results, and sorting.

For a group, let valid members be readable and not marked changed since scan. Let eligible members additionally be writable. If there is no valid keeper or fewer than two valid copies, potential savings is zero. If at least one valid member is not eligible for deletion, that member can be retained and the eligible members' sizes can be counted. Otherwise reserve one eligible member and sum the others; byte-identical members have equal sizes, but calculate defensively from the actual sizes rather than multiplying a screenshot count. Automatic-selection protection/favorites still govern recommendations; potential space is not permission to automatically select protected files. Files are revalidated by the existing deletion coordinator before any removal.

The headline is an estimate based on the stored snapshot, not a promise that those bytes will definitely be deleted. Actual reclaimed bytes come only from successful deletion outcomes.

### 4.2 Selection ownership and filter scope

Use existing Room member selection flags as the single source of truth. No per-card remembered checkbox state and no parallel ViewModel-only selection set that drifts from Room.

Implementation choice for the new global action:

- Selection persists across filters, sorting, search, previews, and normal navigation.
- The bottom action covers selected eligible members across the entire active result set.
- If any selected members are hidden by the current filter/search, add an explicit line: `<N> selected outside this view`, with a `Show all` action that clears filter/search. The confirmation includes this information as well.
- Never silently deselect hidden files, never silently exclude them from a globally labeled count, and never delete hidden selections without exposing their inclusion.
- A no-matching-filter state must still communicate any global selection; it is different from no duplicate groups in the scan.

Do not regenerate automatic selections after every filter/theme change. Initial recommendations continue to come from the existing scanner result mapping and selection policy.

### 4.3 Deletion orchestration

Extend Results to invoke the existing `DeletionCoordinator.delete(groups: List<DeletionGroup>): DeletionResult` once per explicit batch.

- Capture an immutable request snapshot containing the active session ID, all requested selected member IDs, total selected bytes, and the complete members of every involved group, including keepers.
- Keep selected IDs separate from display positions. Never construct requests using list indexes, labels, or shortened paths.
- Read back the current active result snapshot before confirmation/execution. If the session or requested selection changed, dismiss the stale request with an explanation and require a fresh explicit request; do not silently substitute a different selection.
- Respect `confirmBeforeDelete` (currently true by default). The normal flow presents an app confirmation and then any required Android authorization. If the user explicitly disabled app confirmation in settings, keep the existing behavior; do not silently reverse their preference.
- Lock out duplicate delete requests and selection mutations while preparing/executing the batch. Disable clear-results and starting a new scan while that batch is actively managed by this Results flow.
- Keep the existing media-vs-direct deletion mapping: media with an applicable content URI follows the existing authorization path; document handling remains unchanged.
- Do not bypass `DeletionCoordinator`, `AndroidDeletionStorage`, `RoomDeletionResultStore`, or `PendingDeletionReconciler` with direct UI file operations.
- Preserve cancellation propagation. Clear busy state in a `finally` path. Do not automatically retry a destructive batch.
- On success/partial outcome, show actual deleted count, actually reclaimed space, and retained/failed information. Android cancellation does not imply earlier direct deletions were undone.
- An unexpected exception after partial progress must not be presented as “0 deleted” unless that is known. Use a truthful incomplete-operation message and reconcile/read persisted outcomes; do not invent totals.
- If the final group disappears, retain the result notification and show the completed empty Results state. Do not leave a loading spinner waiting for a deleted group.
- Across process recreation, preserve selections through Room and use the existing pending-deletion recovery. Do not replay a stored confirmation automatically. Do not enable another delete while a known pending operation is still being reconciled.
- While a live batch is executing, keep this Results owner alive and prevent Back from canceling it accidentally; explain that deletion is in progress. Dialog Back dismisses an app confirmation before execution; Android authorization retains platform cancellation behavior.

## 5. Navigation and scan-completion contract

### 5.1 Desired stack

```text
Home -> Scanning -> Results -> Preview
Home -> Previous results -> Results -> Preview
Home -> Settings -> Exclusions -> Exclusion browser
Home -> Settings -> Scan locations / File types / Appearance
```

Completion replaces/removes the Scanning entry and leaves Home beneath Results. There must be no path where Back from Results restores a completed Scanning page.

| Current location/state | Back result |
| --- | --- |
| Home | Normal Android exit/background behavior |
| Running scan | Home; worker continues |
| Results, no transient overlay | Home; saved results and selections remain |
| Preview | Same Results entry, same filter/search/sort and list position |
| Settings detail | Its Settings parent |
| Exclusions | Settings |
| Exclusion browser | Existing directory-up behavior first, then Exclusions |
| Search keyboard/dialog/sheet | Dismiss the relevant transient UI before leaving the screen |
| App delete confirmation | Cancel confirmation without changing files |
| Executing deletion | Keep the live operation owner; show in-progress feedback |

Toolbar Back, Android three-button Back, and gesture Back must agree on the applicable screen behavior. Test them independently; the old navigation test's helper clicks the toolbar icon and is not evidence for Android system Back.

### 5.2 Starting a scan without reopening stale results

Use a small explicit scan-entry request rather than `Flow<Unit>` for all entry meanings. Suggested new contract in `Routes.kt`:

```kotlin
data class ScanNavigationRequest(
    val baselineSessionId: String? = null,
    val awaitNewSession: Boolean = false,
)

@Serializable
data class ScanningRoute(
    val baselineSessionId: String? = null,
    val awaitNewSession: Boolean = false,
)
```

The exact class placement can share `Routes.kt`; do not add a general navigation event framework.

- For a new scan, capture the latest session ID **before enqueueing** and mark `awaitNewSession = true`. Keep the scan action guarded while the start request is pending.
- If a running session already exists, resume its status instead of waiting for an unnecessary new session; keep WorkManager's existing unique-work behavior.
- While awaiting a new session, ignore completion of the baseline ID. Bind to a later different session ID, even if it completes so quickly that the UI never observes RUNNING.
- For resume/notification entry, resolve the current relevant session; an already completed current scan can open its active results immediately.
- Add explicit session identity to the scanning presentation state. Derive completion readiness only when the bound session is completed **and** active persisted results belong to the same ID.
- Do not rely solely on observing a RUNNING -> COMPLETED transition; a fast scan or process recreation can miss that transition.
- Handle enqueue failure, cancellation before a database session is created, and no-session notification entry. If needed, expose the current unique work's pending/terminal state through the existing `ScanScheduler`; never leave an infinite “starting” screen.
- Preserve entry/bound session identity across navigation restoration. The existing worker attempt UUID is not the WorkManager worker UUID; do not accidentally compare those unrelated values.

### 5.3 Completion effect and re-entry

- Put navigation in the destination layer, driven by explicit completion readiness and session identity, not inside a drawing composable or scanner engine.
- Run it once for the visible/resumed Scanning destination. Remove Scanning from the stack as Results opens. A restored terminal entry must resolve to Results without duplicating the stack.
- If the user intentionally left Scanning for Home or Settings, do not steal navigation when background work completes. Refresh the previous-results/current-scan presentation; the next explicit entry opens the completed results.
- Never auto-navigate after failure or cancellation as though a new scan succeeded. Previous completed results remain reachable and clearly belong to the previous scan.
- Remove saved-root-tab restoration behavior where it would resurrect an obsolete Scanning stack. Use the existing Navigation Compose APIs, not a library migration.

## 6. File ownership and planned changes

Paths below are repository-relative. Existing filenames are verified. New filenames are proposed deliverables, not files that already exist.

| Files | Responsibility |
| --- | --- |
| `app/src/main/java/com/emma/duplicates/core/designsystem/{Color,Theme,Type,Components}.kt` | Theme roles, type scale, shapes, shared card/action styling |
| `app/src/main/java/com/emma/duplicates/MainActivity.kt` | Theme resolution/system bars, typed scan-entry requests, existing authorization/recovery integration |
| `app/src/main/res/values/{colors,themes,strings}.xml` | Launch background, theme support, new/updated complete resource strings |
| `app/src/main/java/com/emma/duplicates/data/preferences/PreferencesRepository.kt` | Appearance preference only; retain existing defaults and keys |
| `app/src/main/java/com/emma/duplicates/app/{AppNavigation,AppNavigationActions,AppDestinations,Routes,ScanViewModel,ScanWork}.kt` | Simplified graph, session-aware completion and Back behavior |
| `app/src/main/java/com/emma/duplicates/app/HomeViewModel.kt` | Stable Home configuration/storage/previous-result state |
| `app/src/main/java/com/emma/duplicates/ui/home/{HomeScreen,ReadyHomeScreen,StorageSummaryCard}.kt` | Approved Home presentation and accessible reflow |
| `app/src/main/java/com/emma/duplicates/app/ResultsViewModel.kt` | Member-based results, durable controls, selection, batch request state and deletion orchestration |
| New `app/src/main/java/com/emma/duplicates/app/DuplicateResultsMapper.kt` | Shared pure potential-space/member/deletion mappings used by Home/Results; no storage IO |
| New `app/src/main/java/com/emma/duplicates/ui/results/ResultsUiState.kt` | Move existing Results enums/models out of the growing screen file; add member/selection/deletion fields |
| `app/src/main/java/com/emma/duplicates/ui/results/{ResultsScreen,DuplicateGroupCard}.kt` | New summary, lazy group/member list, filters, sorting, sticky action |
| `app/src/main/java/com/emma/duplicates/core/format/DisplayFormatters.kt` | Structured size display and concise presentation-only location formatting as needed |
| `app/src/main/java/com/emma/duplicates/ui/components/{ScreenLayout,ScreenComponents,MediaThumbnail}.kt` | Compact headers, role-based colors, measured layout/insets and bounded preview work |
| `app/src/main/java/com/emma/duplicates/app/SettingsViewModel.kt`, `app/src/main/java/com/emma/duplicates/ui/settings/{SettingsUiState,SettingsScreen,SettingsDetailScreens}.kt` | Appearance access, existing scope controls and simplified settings |
| `app/src/main/java/com/emma/duplicates/ui/exclusions/{ExclusionsScreen,ExclusionBrowserScreen}.kt` | Theme/layout alignment and non-root navigation |
| `app/src/main/java/com/emma/duplicates/ui/permission/PermissionScreen.kt`, `app/src/main/java/com/emma/duplicates/ui/preview/PreviewScreens.kt` | Compact permission copy, readable theme-aware preview/details |
| `app/src/main/java/com/emma/duplicates/data/ScanStore.kt`, `app/src/main/java/com/emma/duplicates/core/database/Daos.kt` | Only a narrow selection/readiness fix if required by inline selection tests; no schema redesign |
| `app/src/main/java/com/emma/duplicates/ui/components/{RootNavigationBar,RootDestination}.kt` | Remove once no production/test references remain |
| `app/src/main/java/com/emma/duplicates/app/ReviewViewModel.kt`, `app/src/main/java/com/emma/duplicates/ui/review/ReviewDuplicatesScreen.kt` | Migrate required logic/tests, then remove the obsolete review-only flow |

Keep `ExactDuplicateScanner`, file discovery/fingerprinting, storage permission scope, external-viewer security, media deletion authorization, and Room schema intact unless a focused regression proves a necessary change. Reuse `AppContainer.deletionCoordinator(authorizer)`; do not introduce Hilt or a new service layer.

## 7. Implementation tasks

Tasks are ordered to make each dependency explicit. Perform targeted verification after each behavior change; run the final integrated checks once the flow is complete. Do not commit at the end of a task unless the user separately requests it.

### Task 1: Reconfirm inputs and establish the implementation baseline

**Files:** This plan; current project instructions; supplied dark, summary, and light references; build/version files.

**Consumes:** User instructions and Section 1 references.

**Produces:** Verified scope, branch/status baseline, selected light reference, exact light role mapping if available.

- [ ] Inspect current branch, HEAD, status, instructions, existing reports, and active work. Preserve the pre-existing crash log and any new unrelated changes.
- [ ] Read applicable files only after the required deterministic secrets scan.
- [ ] Inspect the two approved dark references and the user's supplied light image. Record that the final summary supersedes only the old crowded header.
- [ ] Recheck stack versions and current source ownership. Do not upgrade dependencies to follow a new documentation example.
- [ ] If the light reference is missing, document that narrow missing input and continue work that does not depend on it. Do not select an earlier rejected light palette.
- [ ] Identify an existing connected test device/emulator before planning runtime execution; do not start or replace unrelated emulators.

**Verification:** `git status --short --branch`, `git rev-parse HEAD`, and file existence checks. These are baseline checks, not application test passes.

### Task 2: Implement semantic theme roles and compact shared components

**Files:** Design-system files; `PreferencesRepository.kt`; `SettingsViewModel.kt`; `SettingsUiState.kt`; `MainActivity.kt`; theme/color resources; `ScreenComponents.kt`; `MediaThumbnail.kt`.

**Tests:** Existing `DuplicatesThemeTest.kt`, `PreferencesRepositoryTest.kt`, `SettingsViewModelTest.kt`, and relevant shared-component presentation tests.

**Interfaces:**

```kotlin
enum class AppThemeMode { SYSTEM, LIGHT, DARK }
// Add themeMode to AppPreferences with a backward-compatible DARK default.
suspend fun PreferencesRepository.setThemeMode(mode: AppThemeMode)
@Composable fun DuplicatesTheme(darkTheme: Boolean, content: @Composable () -> Unit)
```

Retain `DuplicatesCard` and `PrimaryActionButton` as the shared component entry points; change their internals to read the current semantic scheme.

- [ ] Update theme tests to assert the approved dark role mapping rather than the old black/gold allowed-palette rule. Do not simply delete the palette test.
- [ ] Add appearance persistence/default tests: existing preferences keep their values, no stored mode resolves to DARK, known modes round-trip, and an unknown stored mode safely falls back.
- [ ] Implement the exact dark tokens and verified light mapping when available. Keep dynamic wallpaper colors off for this fixed approved palette.
- [ ] Apply the shared size/spacing/type contract. Remove raw theme color imports from changed presentation components.
- [ ] Resolve the selected mode once at the app root; system mode follows the current system setting, explicit light/dark override it. Feed the resolved theme into system-bar icon contrast without recreating the navigation graph.
- [ ] Update app/window backgrounds to match the displayed theme as soon as it is resolved. Preserve platform splash requirements; verify cold launch rather than inventing a second splash screen. Keep the launcher artwork itself unless the new background requires a narrowly scoped resource color change.
- [ ] Verify buttons and checkboxes use dark text/check marks on orange. Check actual enabled, focused/pressed, disabled and error states. Retain an accessible semantic error treatment for real errors; the reference does not remove error semantics.
- [ ] Run the targeted tests below. Inspect both selected themes at runtime when their references are available.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.core.designsystem.DuplicatesThemeTest" --tests "com.emma.duplicates.data.preferences.PreferencesRepositoryTest" --tests "com.emma.duplicates.app.SettingsViewModelTest" --no-daemon --console=plain
```

**Acceptance:** No old gold/black-only styling leaks through theme changes; persisted scan settings are preserved; status/navigation icons remain legible.

### Task 3: Fix scan completion routing and Back behavior

**Files:** `MainActivity.kt`, navigation files, `ScanViewModel.kt`, `ScanWork.kt`, `ui/scan/ScanningScreen.kt` state model; `ScanStore.kt` only if a narrow session lookup is needed.

**Tests:** `AppNavigationTest.kt`, `ScanViewModelTest.kt`, `ScanRunnerTest.kt`, `DuplicateScanWorkerTest.kt`; add `ScanSchedulerTest.kt` only if new scheduler observation behavior needs its own tests.

**Consumes:** `ScanNavigationRequest`/`ScanningRoute` from Section 5, existing session/status/result flows.

**Produces:** A completion signal tied to one bound session and results readiness, plus a graph where Home remains beneath Results.

- [ ] Add regression cases with explicit assertions: old completed session A followed by newly requested session B does not open A; B can complete before a RUNNING emission and still opens B; active results must match B before navigation.
- [ ] Add cases for normal completion, zero duplicates, zero indexed files, failure, cancellation, pre-session enqueue/cancel failure, and notification entry after completion.
- [ ] Add stack assertions: completion leaves Home then Results; Back never reopens completed Scanning; repeated completion emissions and rotation do not add more Results entries.
- [ ] Implement typed entry requests and baseline/bound-session handling without changing the exact-duplicate algorithm or worker attempt identity contract.
- [ ] Implement one destination-level completion effect, gated to the visible/resumed destination, and a navigation action that removes the transient scan entry.
- [ ] Remove the root bottom bar; make Results and Exclusions ordinary destinations. Add a Results Back action. Keep Settings reachable from Home and Exclusions from Settings.
- [ ] Test toolbar Back and Android Back dispatcher separately. Add a device gesture check to Task 10.
- [ ] Test that leaving an active scan does not cancel WorkManager, and completion while on Settings does not redirect the user unexpectedly.
- [ ] Remove obsolete root-navigation helpers only after their callers/tests have migrated.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.app.AppNavigationTest" --tests "com.emma.duplicates.app.ScanViewModelTest" --tests "com.emma.duplicates.app.ScanRunnerTest" --tests "com.emma.duplicates.app.DuplicateScanWorkerTest" --no-daemon --console=plain
```

**Acceptance:** A successful scan leads directly to its persisted Results, and every Back path in Section 5 is finite and intentional.

### Task 4: Rebuild Home around scan configuration and saved results

**Files:** Home ViewModel/screens, `StorageSummaryCard.kt`, Home destination wiring, existing settings state/setters, string resources.

**Tests:** `HomeViewModelTest.kt`, `ReadyHomeScreenTest.kt`, `PresentationScreensTest.kt`, relevant Settings tests.

**Interfaces:** Keep the public `HomeScreen` entry point, but adapt its state/callbacks to the consistent layout. Expose a shared configuration state containing storage totals, location summary, enabled categories and `scanEnabled`; a saved-results summary; and optional active-scan progress. Use existing `SettingsViewModel.setCategoryEnabled(category, enabled)` through an explicit `(UiFileCategory, Boolean) -> Unit` callback and navigation callbacks for locations/previous results. Avoid copying preference-write logic into a second owner.

- [ ] Add tests that real storage capacity appears before the first completed scan, multiple selected volumes are labeled accurately, and unavailable capacity is not rendered as a false zero.
- [ ] Add tests that category controls persist changes and cannot disable the last category; the location row opens Scan locations with whole-storage and selected-path choices; Previous scan opens saved Results without enqueueing work.
- [ ] Implement the approved order from Section 3.2 and remove the unwanted promotional strings from all Home states.
- [ ] Reuse the same Home structure before/after scanning instead of separate oversized completed and empty dashboards. Preserve meaningful no-results/no-matching-files messaging.
- [ ] Show compact active-scan progress and disable scan-scope controls during an active attempt. Keep the start guard active while the enqueue request is pending.
- [ ] Use scrollable content at constrained heights; let the category grid become one column when text needs it.
- [ ] Check 320 dp at 100% and 200%, and ordinary width at 200%. Verify click targets and actual on-screen bounds, not only node existence.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.app.HomeViewModelTest" --tests "com.emma.duplicates.ui.home.ReadyHomeScreenTest" --tests "com.emma.duplicates.ui.PresentationScreensTest" --no-daemon --console=plain
```

**Acceptance:** Home uses the approved overview composition and starts/resumes the real scan with accurate scope and storage data.

### Task 5: Build member-based result mapping and the revised summary

**Files:** New `ResultsUiState.kt`, new `DuplicateResultsMapper.kt`, `ResultsViewModel.kt`, `ResultsScreen.kt`, `DisplayFormatters.kt`, Home saved-results mapping, resources.

**Tests:** `ResultsViewModelTest.kt`, `DisplayFormattersTest.kt`; new `DuplicateResultsMapperTest.kt` under `app/src/test/java/com/emma/duplicates/app/`; new `ResultsScreenTest.kt` under `app/src/test/java/com/emma/duplicates/ui/results/` for focused new UI coverage.

**Interfaces:** Move the existing Results enums/models to `ResultsUiState.kt` without changing their package. Extend the result group state with actual members. A member state must carry `memberId: String`, `fileId: String`, filename, category, byte size, full path, display location, thumbnail/content URI, selection state, keeper recommendation and eligibility. Add explicit `availableBytes: Long`, `selectedBytes: Long`, `selectedCount: Int`, `sessionId: String?`, and `hiddenSelectedCount: Int` to the appropriate state, rather than overloading one value.

Pure mapping boundaries in `DuplicateResultsMapper.kt`:

```kotlin
internal fun availableBytes(group: DuplicateGroupWithMembers): Long
internal fun toDeletionGroup(
    group: DuplicateGroupWithMembers,
    selectedMemberIds: Set<String>,
): DeletionGroup
```

The second mapper must include complete group membership and only mark the captured requested IDs as selected. Reuse it in Task 7.

- [ ] Add a fixture equivalent to 108 members in 52 groups and assert that the summary counts include keepers while the selected count is separate. Use small deterministic byte values rather than copying mockup storage into application code.
- [ ] Add potential-savings tests: zero/one valid copy, all writable identical copies, a readable non-writable keeper, unavailable/changed members, selection off, and changing the keeper.
- [ ] Assert checkbox changes alter selected bytes but do not change potential available bytes or reorder groups under largest-potential-savings sorting.
- [ ] Implement member mapping and derive potential totals from actual members. Preserve real category filters, search behavior and deterministic sort tie-breakers.
- [ ] Render the approved summary hierarchy with scalable typography, separately labeled statistics, and a merged accessible size announcement.
- [ ] Add a structured size result to `DisplayFormatters` only if required for numeric/unit styling; share its existing unit thresholds and rounding. Test zero bytes, kB/MB/GB/TB transitions, large values and formatting consistency.
- [ ] Make summary/filters/list one scrollable content sequence. Assert the summary scrolls out and the footer remains reachable.
- [ ] Cover no saved scan, zero duplicates, zero indexed files, and a filter with no matches as different states.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.app.ResultsViewModelTest" --tests "com.emma.duplicates.app.DuplicateResultsMapperTest" --tests "com.emma.duplicates.core.format.DisplayFormattersTest" --tests "com.emma.duplicates.ui.results.ResultsScreenTest" --no-daemon --console=plain
```

**Acceptance:** The final approved summary is implemented, truthful, and readable; selected bytes and total potential bytes have distinct meanings.

### Task 6: Inline file selection, lazy group rows, and preview restoration

**Files:** `ResultsScreen.kt`, `DuplicateGroupCard.kt`, Results models/ViewModel, `MediaThumbnail.kt`, navigation destination wiring, formatting helper; `ScanStore.kt`/`Daos.kt` only for a proven selection-boundary gap.

**Tests:** Results ViewModel/UI tests, `AppNavigationTest.kt`, `MediaThumbnailTest.kt`, existing `ScanStoreTest.kt` and `DuplicateSelectionPolicyTest.kt`; migrate relevant Review tests.

**Interfaces:**

```kotlin
fun ResultsViewModel.changeSelection(memberId: String, selected: Boolean)
// ResultsScreen callbacks:
onPreviewFile: (groupId: String, fileId: String) -> Unit
onMemberSelectionChange: (memberId: String, selected: Boolean) -> Unit
```

Keep `ScanStore.setMemberSelected(memberId: String, selected: Boolean): Boolean` authoritative and react to its returned rejection.

- [ ] Add tests for selecting/deselecting actual member IDs, preventing the final valid keeper from being selected, changing the keeper through the safe sequence, and preserving protected automatic-selection behavior.
- [ ] Make eligibility checks consistent between UI and persistence. The inspected DAO guards the other valid keeper; verify the selected target's readable/writable/unchanged state at the same boundary before accepting a newly selected target. Add only the targeted fix if that regression fails.
- [ ] Replace representative-only group cards and Review buttons with member rows. Use stable IDs, lazy member composition, and top/bottom shapes that make each group appear as one card.
- [ ] Preserve the recommended keeper-first order and do not reorder rows after a checkbox update.
- [ ] Implement concise location labels with distinguishing parents/volume when needed; keep canonical paths intact in data and preview. Test same-name files in different parents and same parent suffix on different volumes.
- [ ] Preserve lazy audio artwork and size-bounded video thumbnail requests. Share one screen-level loader; do not create one per row or decode media synchronously in composition.
- [ ] Persist small filter/sort/query state via the existing navigation entry's `SavedStateHandle`; keep selection in Room. Preserve `rememberLazyListState`/saveable state for Preview round trips and recreation, resetting for a genuinely new result session.
- [ ] Show global selection and hidden-selection count consistently when search/filter hides members. `Show all` clears both search and category filtering without changing selections.
- [ ] Test that each thumbnail opens its own file, does not toggle selection, and Back returns to the same visible anchor and controls. If the previewed file/group disappears, show the existing unavailable state rather than indefinite loading.
- [ ] At 320 dp/200%, allow metadata to move beneath the filename/thumbnail and keep the selection target distinct. Do not shrink it below the minimum touch area.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.app.ResultsViewModelTest" --tests "com.emma.duplicates.ui.results.ResultsScreenTest" --tests "com.emma.duplicates.app.AppNavigationTest" --tests "com.emma.duplicates.ui.components.MediaThumbnailTest" --tests "com.emma.duplicates.data.ScanStoreTest" --tests "com.emma.duplicates.domain.selection.DuplicateSelectionPolicyTest" --no-daemon --console=plain
```

**Acceptance:** Review and selection happen directly in Results, one valid copy is retained, and preview/navigation do not reset the review context.

### Task 7: Wire the global deletion action safely

**Files:** `ResultsViewModel.kt`, Results models/screen/dialogs, `AppDestinations.kt`; existing deletion services reused; `MainActivity.kt` recovery readiness exposure only if needed to prevent a second operation during reconciliation.

**Tests:** Results tests; migrate deletion mapping/confirmation cases from `ReviewViewModelTest.kt`; retain `DeletionCoordinatorTest.kt`, `PendingDeletionReconcilerTest.kt`, `RoomDeletionResultStoreTest.kt`, `DeletionRecoveryGateTest.kt`, `AndroidMediaDeletionAuthorizerTest.kt`.

**Interfaces:** Inject the existing `PreferencesRepository` and `DeletionCoordinator` into `ResultsViewModel`, with `SavedStateHandle` for small restored controls. Expose `requestDelete()`, `dismissDelete()`, `confirmDelete()`, and `dismissDeletionResult()` callbacks. Hold the immutable pending batch privately in the ViewModel; UI state exposes its summary, busy state, confirmation visibility and truthful outcome.

- [ ] Test two selected groups with complete keeper membership passed to one `DeletionCoordinator.delete` call. Assert unselected groups/files are not marked for deletion and preview IDs are never confused with member IDs.
- [ ] Test that global counts, hidden-selection notice, confirmation counts and captured request IDs agree after filtering/searching.
- [ ] Test default confirmation, disabled app-confirmation preference, canceled confirmation, repeated rapid taps, zero selection, changed active session before confirmation, and stale requested selection.
- [ ] Implement request preparation and immutable capture, recheck on execution, and a synchronous/serialized busy guard before launching work. Ignore a second request while busy.
- [ ] Reuse existing media/direct routing and coordinator/recovery mechanisms. Keep all validated keepers in the request, including filtered-out kept rows.
- [ ] Add tests for successful deletion, partial direct/media outcome, Android authorization canceled, vanished/changed files, inaccessible keeper, and unexpected failure after checkpointed progress. Assert accurate or explicitly unknown outcomes, not invented “all deleted”/“none deleted”.
- [ ] Keep result feedback alive when groups disappear; update totals/list from Room and leave a meaningful zero-duplicates Results state after the last group is resolved.
- [ ] Test Back and rotation while the operation awaits Android authorization. Keep the live owner, avoid duplicate platform dialogs, and use pending reconciliation after process recreation rather than replaying a destructive event.
- [ ] Disable conflicting selection/clear/new-scan actions while a live batch is managed. When recovery is known to be pending, keep deletion unavailable until the existing reconciliation completes.
- [ ] Migrate remaining single-group Review behavior/tests and remove its obsolete navigation entry and production classes only after no callers remain.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.app.ResultsViewModelTest" --tests "com.emma.duplicates.ui.results.ResultsScreenTest" --tests "com.emma.duplicates.domain.deletion.DeletionCoordinatorTest" --tests "com.emma.duplicates.domain.deletion.PendingDeletionReconcilerTest" --tests "com.emma.duplicates.data.RoomDeletionResultStoreTest" --tests "com.emma.duplicates.DeletionRecoveryGateTest" --tests "com.emma.duplicates.app.AndroidMediaDeletionAuthorizerTest" --no-daemon --console=plain
```

**Acceptance:** Users can delete reviewed copies across groups from Results using the existing safety pipeline, with one valid keeper per group and honest partial-outcome reporting.

### Task 8: Compact scan UI, Settings, permission, exclusions, and preview polish

**Files:** `ScanningScreen.kt`, Settings UI/state/wiring, Exclusions screens, Permission screen, Preview screens, shared components, resources.

**Tests:** `PresentationScreensTest.kt`, `SettingsDetailScreensTest.kt`, `PermissionScreenTest.kt`, `AppNavigationTest.kt`; use focused additions instead of introducing a new broad UI-testing framework.

- [ ] Replace the large scan progress ring and stacked metric/phase cards with the compact truthful progress layout from Section 3.3.
- [ ] Ensure completion no longer exposes an obsolete primary Back button; canceled/failed states keep meaningful actions and previous results.
- [ ] Add the small Appearance setting and apply the same theme rules to settings, permission, exclusions, browsers, previews, menus, sheets and dialogs.
- [ ] Keep Exclusions accessible through Settings, with add/remove behavior and browser directory Back intact. Replace obsolete root-tab expectations with this path.
- [ ] Reduce repetitive explanatory text while preserving essential permission/deletion facts. Use complete resource strings and plurals for all new counts and actions.
- [ ] Run the existing prohibited-punctuation test. Do not relax it to allow dot separators or forbidden dashes from a mockup.
- [ ] Verify TalkBack names/states for Keep/selected controls, file-specific preview targets, group headings, progress and outcome messages. Avoid repeated announcements for every small progress update.
- [ ] Cover 320 dp, 200%, and the combination for sheets/dialogs as well as full screens. A reachable scroll action is acceptable; clipping is not.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.emma.duplicates.ui.PresentationScreensTest" --tests "com.emma.duplicates.ui.SettingsDetailScreensTest" --tests "com.emma.duplicates.ui.permission.PermissionScreenTest" --tests "com.emma.duplicates.StringResourcePolicyTest" --tests "com.emma.duplicates.app.AppNavigationTest" --no-daemon --console=plain
```

**Acceptance:** No secondary route falls back to the old giant black/gold UI, and all existing essential controls remain reachable.

### Task 9: Complete the light counterpart from the supplied reference

**Files:** Same theme and resource owners as Task 2; screen changes only when required for a real foreground/background issue.

**Consumes:** The user's selected light image and the accepted revised summary layout. This task does not authorize inventing another light design.

- [ ] Bind light role values to the supplied image and verify the same hierarchy, card grouping and orange interaction language.
- [ ] Test theme switching on Home, Results, Preview and Settings without losing scan/result state, current filters, selection or list position.
- [ ] Check cold launch, background/resume and system appearance changes with the chosen mode. Verify status and navigation bar contrast.
- [ ] Run theme/preference and relevant Results presentation tests for both modes; keep all data/behavior paths shared.
- [ ] Capture the minimum light reference-comparison evidence. If the reference is still missing, record the exact unresolved input and do not mark this task complete.

**Acceptance:** Both user-selected appearances are implemented through one design system and one functional flow, with no guessed light-theme approval.

### Task 10: Integrated validation, evidence, and bounded cleanup

**Files:** Existing tests and any narrow regression additions above; update README only if its user-facing navigation description or documented verification would otherwise be false. Keep one concise implementation report in the repository's existing report convention.

- [ ] Run the full debug JVM suite once after integrated changes because shared themes, navigation, Room-facing selection and deletion entry paths changed.
- [ ] Run the existing direct Gradle formatting/static checks and debug build. Do not invoke unrelated security scans, dependency upgrades, or scanner-wrapper suites as a substitute for these checks.
- [ ] Update and run the existing instrumented launch/navigation coverage plus the retained database migration check on the identified device/emulator. New UI behavior tests must verify the new flow, not the removed root tabs.
- [ ] Run the runtime matrix in Section 8 with isolated task-owned fixture files. Do not scan/delete personal photos to obtain screenshots or proof.
- [ ] Compare implemented Home and Results against the accepted overview and revised summary. Compare light only against the actually supplied selected light reference.
- [ ] Record tests that ran, exact commands/results, device/API and viewport/font settings, verified scenarios, skipped cases with reasons, and any remaining issue. Prior README claims are not fresh evidence.
- [ ] Remove disposable task-owned fixtures, intermediate screenshots/logs/build copies and temporary scripts. Retain only the final deliverable and minimal evidence. Preserve pre-existing files and shared build/package caches.
- [ ] Restore any changed device display/font settings after recording their original values. Stop only temporary processes started for this task; do not kill unrelated Java, Gradle, Node, emulator, IDE, browser or MCP processes.
- [ ] Inspect `git diff --check`, the final scoped diff and `git status --short --branch`. Do not commit or push without separate authorization. Never create or merge pull requests.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug --no-daemon --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
git diff --check
git status --short --branch
```

If a prerequisite or unrelated baseline failure blocks a command, report it accurately. Do not disable checks, delete failing tests, or broaden into unrelated repairs to produce a green report.

## 8. Validation matrix and concrete acceptance scenarios

### 8.1 Layout matrix

| Configuration | Required checks |
| --- | --- |
| Ordinary compact phone, 100% font | Home order, full summary hierarchy, compact member rows, sticky action, both selected themes |
| 320 dp width, 100% font | All filters reachable, filenames/locations distinguishable, no overlap with selection controls |
| Ordinary width, 200% font | Summary and footer grow/reflow, category labels fit, dialogs scroll when needed |
| 320 dp width, 200% font | Home category controls can stack; summary statistics can stack; final file row and delete action reachable |
| Landscape/short height | Summary scrolls away, no content trapped behind footer, preview/confirmation actions reachable |
| System navigation variants | Three-button Back and gesture Back agree; footer clears navigation area |
| Theme change/recreation | State preserved; colors/system icons update without resetting results or replaying completion/deletion |

Do not impose “two full groups must be visible” at 200% font scale. The image is a normal-scale composition reference; readability and access take priority.

### 8.2 Functional scenarios

1. Fresh install/access missing: compact rationale, grant access, return to accurate Home, selected categories visible, no automatic file deletion.
2. Start a first scan: real progress, automatic Results after completion, no extra Results tab or button required.
3. Start another scan with old completed results present: never flash/open those as the new outcome; fast completion works even if RUNNING was not observed.
4. Leave an active scan using toolbar Back, then system Back in a separate run: worker continues; Home shows status; opening it resumes current work.
5. Complete while on Settings: remain on Settings; returning Home exposes current saved results. Notification entry resolves correctly.
6. Cancel/fail a scan: previous completed results remain available; no success claim or empty overwrite of the previous result set.
7. Open each copy in a group: preview the exact intended file, return to the same list location, keep current filter/search/selection.
8. Change the keeper: unselect one duplicate, select former keeper, verify at least one valid unselected copy remains. Selecting the final valid keeper is rejected clearly.
9. Filter/search after selecting across groups: global selected count stays correct; hidden selections are explicitly disclosed; Show all reveals them without changing selection.
10. Delete task-owned copies from multiple groups: confirmation and platform requests match the intended snapshot; actual group/session totals refresh.
11. Cancel Android media authorization after a mixed operation: report partial completion correctly; do not say all files were untouched if direct deletions already succeeded.
12. Modify/remove a fixture between scan and deletion: existing revalidation prevents unsafe deletion and reports retained/unavailable outcomes.
13. Rotate/recreate with selections, an open preview, confirmation, and pending platform deletion in separate tests: preserve durable state and do not repeat destructive requests.
14. No duplicates, no indexed files, no saved results, and no filter matches: distinct useful messages and valid next actions.
15. Long paths, identical filenames, non-ASCII names, large byte values, three-or-more copies, document/audio fallback, and a large single group: correct identities, stable rows and bounded preview work.
16. TalkBack: group context, file identity, Keep/selected state, blocked last-copy reason, delete count/size and dialog outcome are understandable without color.

### 8.3 Required evidence, kept small

- One final Home and one Results screenshot per implemented theme at ordinary scale.
- One screenshot of the critical 320 dp/200% Results/footer state and one of Home if their reflow differs materially.
- A compact written record for navigation, completion, selection, preview restoration and fixture-only deletion outcomes.
- Exact test commands and a short passed/failed/skipped summary, referencing normal Gradle outputs rather than copying whole build directories.
- No retained user-media samples, duplicate APK archives, repeated timestamped build exports, or logs containing private paths unless strictly required and approved for that evidence.

## 9. Completion checklist

- [ ] Approved dark palette is mapped exactly to its intended roles.
- [ ] Selected light reference has been supplied, implemented and verified, or its absence is explicitly reported as unfinished scope.
- [ ] The unwanted Home promotional texts are absent.
- [ ] There are no word-separator bullets/middle dots or colored card-side stripes.
- [ ] Home exposes real storage, scope, file types, scan action and saved results in the approved order.
- [ ] Successful scan completion automatically opens that scan's persisted Results.
- [ ] Back works through toolbar, system button and gesture paths without reviving completed Scanning.
- [ ] No root bottom navigation is required for the scan-to-results flow; Exclusions remains reachable in Settings.
- [ ] The revised summary uses caption, size and separately labeled statistics, and scrolls with the list.
- [ ] Potential available bytes, duplicate-member counts and selected deletion bytes are mathematically distinct and tested.
- [ ] Every duplicate member can be reviewed/previewed/selected directly from Results.
- [ ] Last-copy protection, auto-selection preferences, Android authorization and deletion recovery remain intact.
- [ ] Filter-hidden selections are explicit and deletion scope matches the displayed confirmation.
- [ ] Preview/theme/recreation preserve relevant state; no destructive action replays automatically.
- [ ] Permission, empty, failure, canceled, loading, unavailable-media and partial-deletion states are implemented.
- [ ] 320 dp, 200% font, and their combination have actual evidence; unverified cases are not called passed.
- [ ] Relevant tests, static checks, build and available device checks pass or have precise reported blockers.
- [ ] Task-owned temporary files/processes are cleaned up, unrelated user work is preserved, and the final deliverable location is clear.
- [ ] No unauthorized commit, push or PR operation occurred.

## 10. Planning verification and documentation references

This plan was based on source inspection and the user's accepted design conversation. Workspace files read during planning were checked with the required deterministic secrets scanner. Planning did not run a build, app test, emulator session, physical-device check, deletion, or application code modification. The reported Back failure remains a user-observed runtime issue; source inspection identifies the current routing and required regression coverage, not a proven device-specific root cause.

Implementation guidance was checked against primary Android documentation:

- Back stack operations and removal of transient destinations: [Navigation and the back stack](https://developer.android.com/guide/navigation/backstack).
- Small restorable UI state versus persistent application data: [Save UI state in Compose](https://developer.android.com/develop/ui/compose/state-saving).
- Material semantics and minimum 48 dp interactive targets: [Compose accessibility API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

These links support the narrow platform behavior discussed here. They do not authorize dependency upgrades or replacing the repository's existing stack.
