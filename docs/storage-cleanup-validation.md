# Storage cleanup expansion validation

Implementation date: 2026-11-03. This describes the uncommitted working tree on `codex/results-selection-clarity`, based on `8300bb6f383ce5f83b39eb2df8bb6b30e7a47ee1` with the user's existing UI text changes preserved. No commit, push, pull request, signing change, SDK update, or dependency update is part of this work.

## Implemented behavior

| Tool | Behavior and boundaries |
| --- | --- |
| Empty folders | Postorder inspection includes zero-byte and hidden entries when deciding whether a tree is empty. Exclusions, unreadable entries, symlinks, volume/scope anchors and recovery-original ancestors block removal. Selecting a parent selects its eligible descendants. Final removal is directory-only and nonrecursive. |
| Large files | Strictly greater than 100 MB, 500 MB or 1 GB, with stable size/date sorting and explicit selection. |
| Storage usage | One whole volume, allocated file-byte categories, conservative Other used space, and read-only immediate-folder drill-down. Incomplete, inconsistent or changing measurements do not fabricate a remainder. |
| Similar photos | Bounded orientation-aware dHash64, distance at most six and symmetric aspect difference at most five percent. Seven-band candidates feed deterministic complete-link grouping. Favorite, resolution, size and path choose a protected keeper; preview can change it. No automatic selection. |
| Screenshots | Validated MediaStore relative paths or recognized screenshot locations; filenames alone do not establish membership. Calendar age controls use the captured run time and zone; unknown/future dates appear only in All. |
| Old downloads | Recognized public Download locations intersected with captured scope. Six-month, one-year and All filters; APKs remain ordinary files and never launch an installer. |
| Unused apps | On-demand Usage access, scoped launcher-package visibility, sufficient known activity/service history, conservative eligibility, optional app-plus-data size, and Android app details. No uninstall operation or broad package permission. |

Home, Tools and Recovery form the root navigation. Exact scan setup remains behind Duplicates. Exact duplicate proof is unchanged. New sessions are isolated from exact result tables and share one WorkManager scan slot. All mutations share the existing operation gate. Ordinary cleanup freezes selection, hashes the original, journals the intended move and recovery identity before rename, then verifies the staged payload against its own hash and filesystem identity. Recovery moves report zero bytes freed immediately. Overlapping results are invalidated. Schema 5 is additive; recovery payloads remain independently accessible.

## Verification evidence

Final post-review integration passed on JDK 21.0.12 with `--no-daemon --console=plain --continue`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-daemon --console=plain --continue
```

- JVM: **296 tests, zero failures, errors or skips**. This includes all three review regressions below, the existing exact-duplicate/recovery suite and the layout matrix.
- ktlint and Detekt passed. Debug and release Android Lint each reported zero errors and four warnings: three plural-candidate heuristics for invariant “selected” labels or the fixed 30/90/180-day choices, and the existing `UseKtx` suggestion in `AndroidMediaDeletionAuthorizer`. No checks were disabled.
- Debug, unsigned release (including shrinking), and instrumentation APKs built. The final debug and instrumentation APKs were installed on the task-owned API 36 emulator; **all seven instrumented tests passed without skips**.

Verified during implementation:

- API 36 instrumentation: existing migrations plus 1-to-5 and 4-to-5 migration preservation passed. Four storage tests passed: directory-only removal with a new-file race, unique-file recovery roundtrip, real ImageDecoder resized/recompressed/mild-edit/orientation/corrupt/low-detail cases, and the real ToolScanRunner's captured selection plus canceled-replacement preservation.
- Actual API 36 UI: Home to Unused apps, app-specific Android Usage access settings, grant and return, and revoke and return worked. The UI changed from Usage access required to No eligible apps, then Usage access revoked. This emulator has no app with sufficient 90-day history.
- Actual ordinary-media authorization: a task-owned screenshot displayed its real thumbnail, and its separate selection control opened Android's photo-write authorization. Deny left the original unchanged and reported zero moved. Allow moved one file, reported zero bytes freed, and invalidated the result. Recovery showed the 30-day expiry; restoring recreated the original with identical SHA-256 (`fe697c7c08667161e4a60adc3f797caa21aa07837f185719d4453beac91e98e4`) and retained its recovery copy.
- API 36 recreation/input: portrait-landscape-portrait preserved the All filter and explicit selection. Background/resume preserved Recovery. ADB-injected Tab focused preview and selection separately; Space toggled only selection and Enter opened confirmation. This is emulated keyboard input, not physical keyboard/switch-access or human TalkBack evidence. The Recovery header inset was visually confirmed after its fix.
- API 36 process interruption: force-stop during the real OS photo-write dialog terminated the app. Accepting the remaining OS dialog afterward did not move the file; its SHA-256 stayed unchanged. Restart retained the selected database row, with the hidden-selection count shown under the default age filter. This verifies interruption before mutation, not an OS kill during atomic rename.

The visual matrix retains **96 latest PNGs** in `app/build/outputs/storage-cleanup/visual/`: Home and seven new screens at 411 dp and 320 dp portrait, short landscape, 100% and 200% font scales, light and dark. The tests exercise independent selection/preview targets, footer reachability, keeper distinction and rendered text-line bounds. State cases cover scanning, failed, missing access, empty results, Home's access action, usage-access variants and file/folder confirmation dialogs. The nine illustrated panels and opposite themes were compared with the three approved boards in `docs/superpowers/plans/storage-cleanup-reference/`; the screenshot grid was corrected to compact cards with independent overlaid selection. Actual API 36 Home was inspected in both themes, and the final screenshot-card composition displayed its real local image.

Visual fixtures use generated local images and Coil's [documented preview handler](https://coil-kt.github.io/coil/compose/) for deterministic screenshot rendering. They do not establish production image loading; real decoding is covered separately by Android instrumentation. Counts, paths, images and app names in visual fixtures are synthetic.

## Final review and regression fixes

One fresh read-only reviewer inspected the implementation and plan. No critical issue was identified. The author verified and fixed the following in one pass; each new test failed before its fix and passed in the final 296-test suite. No second review was substituted for regression tests.

| Verified finding | Fix and regression |
| --- | --- |
| A source removed externally after pre-move interruption could leave the journal and shared operation gate pending forever | Confirmed absence now invalidates the stale result and releases the operation; unavailable volumes still defer. `missingSourceAfterPreMoveInterruptionReleasesGateAndInvalidatesResult` also proves a subsequent scan can start. |
| Hidden-selection counting traversed and sorted all results once per selected item on the UI thread | Visible IDs are collected once. `hiddenSelectionCountProcessesLargeResultsWithoutRepeatedFullTraversals` verifies the count and bounded traversal over 2,000 selected entries; the failing version performed 4,002,000 reads. This is not a device timing benchmark. |
| Failed/canceled run snapshots accumulated until a successful replacement | Terminal completion also prunes older attempts while preserving latest status, active results/selection, other tools and journal references. `terminalAttemptsRetainOnlyLatestStatusActiveResultsAndJournalReferences` covers these boundaries. The reviewer called this minor; it was treated as required because captured path/exclusion scopes may be large and retention must remain bounded. |

There are no deferred code-review findings. Apparent missing bottom-tab content in some inline image previews did not establish a production defect: actual device tabs and semantics were present, and the latest PNGs contained drawn content in all three tab regions. At 320 dp with 200% text, Recovery wraps across lines; scaling and reachable controls were retained. Device evidence limitations below remain explicit.

## Implementation decisions

- Android's public `Os` API has no `rmdir`. Empty-folder removal uses a `SecureDirectoryStream` opened from the captured volume root and relative `NOFOLLOW_LINKS` traversal, followed by `deleteDirectory` (`unlinkat` with `AT_REMOVEDIR` on Android). Unsupported providers skip safely; there is no generic file-delete fallback.
- Real Android testing showed direct 9-by-8 decoding was unstable across JPEG resize sampling. A bounded 72-by-64 decoded image is area-averaged into the same 9-by-8 dHash input. The algorithm/version and matching limits remain explicit in stored results/scope.
- The light-theme selected tab uses a darker orange for readable small text. Compact widths, large text and short landscape reflow cards and footers instead of preserving a fixed phone silhouette. English labels and real system insets differ from the Finnish presentation boards. Synthetic image/app contents are not production constants.
- Work remained in the existing feature checkout to preserve the original uncommitted text changes. No duplicate checkout or unrelated refactor was created.
- The ten plan phases were the execution units. The final review used the working-tree diff and new files rather than an empty commit range, because committing was not authorized. The existing user's text-only changes were identified separately for the reviewer.

## Remaining platform evidence

Only API 36 was available as an installed bootable image; the API 30 and API 37 system-image directories were empty. The physical Pixel 9 and unrelated emulator were not touched. No physical removable/readonly storage, OEM-specific usage history, human TalkBack, hardware keyboard or switch-access session was available. Automated semantics/layout checks are not human accessibility verification.

Actual app-details navigation from an eligible unused-app card is not established on this fresh emulator because no app has the required history. Synthetic policy/UI tests do not establish an OEM's history completeness. OS process death during the atomic-move checkpoint, physical detach/read-only transitions and signed-release runtime acceptance remain separate device evidence. The older API 36 exact-image deletion failure recorded in `PROJECT.md` is not claimed resolved by ordinary-file tests.

## Retained output and cleanup

Retained deliverables:

| Output | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `DD8B6ABCC95928BD41804041A8D8FDAEE6AA985D470EDF6C1C2A9B4D33DDA633` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `58427F458C5ADA562FD440E4DD915DAF702E5F5D69465AE7ECCACF55F68B021C` |

The release APK is unsigned; successful shrinking/building does not establish signed-release runtime acceptance. Standard test reports, the final instrumentation APK, schema 5 and the 96 comparison images remain in their normal project locations. No archive or duplicate APK export was created.

The task-owned API 36 emulator was stopped through its explicit `emulator-5592` serial. Its launcher, headless QEMU, console and crash handler processes were verified absent. All task Gradle commands finished; no temporary server or watcher remains.

**Cleanup blocker:** automatic approval review rejected deletion of `.superpowers/sdd/2026-11-03-storage-cleanup-expansion/`, including a retry using its exact absolute literal path. The only stated reason was `blocked by policy`. The resolved path and all ancestors had already been checked, and its subtree contained no junctions or symlinks. The scratch tree therefore remains, including the disposable AVD, synthetic device fixture, diagnostic logs, preview image, extracted inspection JAR, formatter script and execution/review notes. It is untracked and is not a deliverable. The restriction was not bypassed. Unrelated devices, emulators, processes and original user files were preserved.
