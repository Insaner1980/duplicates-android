# Duplicates

Duplicates is a native Android utility for finding byte-identical files in mounted shared storage and removing reviewed redundant copies. It scans photos, videos, audio, documents, archives, installers, and other ordinary user files. It does not use filenames, timestamps, media metadata, or visual similarity as evidence that two files are duplicates.

All scanning and comparison happens on the device. The app has no `INTERNET` permission, backend, analytics, advertising, account, or cloud upload.

## Exact duplicate detection

A duplicate group passes every stage below:

1. Files are grouped by exact byte length. Unique sizes are not hashed.
2. Size candidates receive a SHA-256 quick fingerprint. The file size is included in the digest input. Files larger than 128 KiB contribute the first and last 64 KiB. Smaller files contribute their complete contents.
3. Remaining candidates are streamed through full SHA-256 with 64 KiB buffers. Full hashing uses at most two workers.
4. Files with the same size and full digest are compared byte for byte before a group is confirmed.

The persistent fingerprint cache is keyed by canonical path and storage volume. A cached result is reused only when the path, volume, byte length, and last-modified time still match and the current file snapshot is valid.

## Android support

| Setting | Value |
| --- | --- |
| Minimum | Android 11, API 30 |
| Compile SDK | Android 17, API 37 |
| Target SDK | Android 17, API 37 |
| Verified emulator | Google Pixel 9, Android 17, API 37 |

The final connected run contains two passing tests on the Pixel 9 API 37 emulator: a UI smoke test for launch, the storage permission explanation, and root navigation, plus the Room 2 to 3 migration test. A separate manual runtime check used isolated fixture directories under `DCIM`, `Pictures`, and `Movies`: a real scan found 9 files in 3 exact groups, photo preview opened, one approved 1.2 MB duplicate was deleted, and Room results updated. The check also covered denied and allowed notification states, notification tap and Stop, canceled-result preservation, 200 percent font scale, landscape, and exclusion add and remove. All fixture directories were removed and their absence was verified. Automated tests do not delete unrelated device files.

## Prerequisites

- JDK 17
- Android SDK Platform 37
- An Android SDK path configured through `local.properties` or `ANDROID_HOME`
- An API 30 or newer device or emulator for installation
- A connected emulator or device for `connectedDebugAndroidTest`

On Windows, `gradlew.bat` uses `JAVA_HOME`, then Java on `PATH`. If neither is
available, it uses Android Studio's bundled JDK from
`%ProgramFiles%\Android\Android Studio\jbr` when installed there. An explicitly
configured but invalid `JAVA_HOME` is reported as an error.

The checked-in Gradle 9.8.2 wrapper downloads build dependencies when they are not already cached. That build-time download does not add network access to the installed app.

## Build and test

### Local lint checks

With PowerShell 7 and the shared `Android-check` installation next to this repository
(or its location set in `ANDROID_CHECK_HOME`), run:

```powershell
.\tools\lc.ps1 -PlanOnly
.\tools\lc.ps1
.\tools\lc.ps1 -Full
```

The existing PowerShell profile also exposes this command as `lc` or `lint-check`.
The default run checks ktlint, detekt, and Android Lint for debug. `-Full` also
checks release Android Lint. Reports are written to the ignored `reports/`
directory. `-PlanOnly` shows the scope without running the analyzers.

### Local Android security checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\ac.ps1 -PlanOnly
.\tools\ac.ps1
```

The existing PowerShell profile exposes this as `ac` or `android-check`.
It runs the project's Semgrep manifest rules and mobsfscan over `app/src/main`,
`app/src/test`, and `app/src/androidTest`. The manifest rules flag enabled backup,
debugging, cleartext traffic, and any exported content provider in main or release.
Reports are written to `reports/security-android-semgrep.txt` and
`reports/mobsfscan.txt`, with corresponding JSON files. The shared installation
must provide its pinned Semgrep and mobsfscan tools; missing tools are reported
as errors. DeepSec is separate and is not invoked by `ac`.

### Local dependency checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\dc.ps1 -PlanOnly
.\tools\dc.ps1
```

The existing PowerShell profile exposes this as `dc` or `dependency-check`.
Use the checked-in `gradle/verification-metadata.xml` by default. Reserve
`-InitVerification` for an explicit dependency update that requires new checksums
or signing-key metadata. This option generates metadata and then runs OSV-Scanner
and OWASP Dependency-Check. Review generated metadata against trusted artifact
sources before relying on it; generation alone does not establish trust.

Gradle verification and OWASP cover `:app`'s `debugRuntimeClasspath` and
`releaseRuntimeClasspath`. OSV scans supported dependency files recursively and
requires coverage of the verification metadata. Reports are written under
`reports/`, including `dependency-verification.txt`, `osv.txt`, and
`security-deps.txt`. The shared installation supplies the pinned OSV-Scanner.
OWASP uses NVD with optional `NVD_API_KEY` from the environment and the shared
cache selected by the runner. OSS Index is disabled; NVD and OSV provide the
vulnerability checks. Missing metadata, tools, or failed services are reported as
errors rather than a successful check.

### Local secret checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\ss.ps1 -PlanOnly
.\tools\ss.ps1
```

The existing PowerShell profile exposes this as `ss` or `secret-scan`.
It runs Gitleaks, TruffleHog, and Semgrep secrets against the working tree,
including non-generated Git-ignored files. Gitleaks and TruffleHog also check
all reachable local Git history by default; no `-History` flag is needed.
`-PlanOnly` lists the exact scope and exclusions without running scanners.
The shared installation supplies the pinned tools and redacts secret values
from published reports. Reports are written under `reports/` as `gitleaks.txt`,
`trufflehog.txt`, `semgrep-secrets.txt`, `gitleaks-history.txt`, and
`trufflehog-history.txt`. Missing tools are reported as errors.

### DeepSec checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\ds.ps1 -PlanOnly
```

The existing PowerShell profile exposes this as `ds` or `deep-sec` and supplies
your configured Codex OAuth provider, data scope, cost estimate, and retention
settings. Run `ds -PlanOnly` to inspect those values before starting a scan.
The `.deepsec` workspace uses project ID `duplicates-android` and inspects this
repository. Its review instructions focus on storage boundaries, exact duplicate
validation, deletion authorization, keeper preservation, recovery, and URI grants.
DeepSec and its Codex runtime are pinned to versions allowed by the shared runner;
processing and revalidation use one worker. To restore dependencies, run
`pnpm --dir .deepsec install --frozen-lockfile --ignore-scripts`.

By default, `ds` runs the custom report pipeline: scan, Codex analysis, and Markdown
export. `-Full` or `-Report` selects the full report pipeline, `-Scan` selects
candidate scanning, and `-Revalidate` rechecks existing findings at HIGH severity
or above. The custom and full pipelines currently contain the same steps.
The runner writes `reports/deepsec.txt`; local analysis data and exported findings
stay in the ignored `.deepsec/data/` and `.deepsec/findings/` directories.

Direct `tools/ds.ps1` calls require per-run external AI settings before execution:
`-AllowExternalAI`, `-Provider`, `-ExternalAIDataScope entire-project-working-tree`,
`-ExternalAICostEstimate`, and `-ExternalAIRetentionPolicy`. Supply the actual
provider, maximum cost estimate, and retention policy for the intended run.
`-PlanOnly` displays this requirement without scanning or invoking a provider.

### Local MobSF checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\ms.ps1 -PlanOnly
.\tools\ms.ps1
```

The existing PowerShell profile exposes this as `ms` or `mobsf-scan`.
It runs the same mobsfscan static analysis included in `ac`, covering
`app/src/main`, `app/src/test`, and `app/src/androidTest`. Reports are written to
`reports/mobsfscan.txt` and `reports/mobsfscan.json`. The shared installation
supplies the pinned mobsfscan and Semgrep Windows runtime. Missing tools are
reported as errors. `-PlanOnly` shows the scope without running the scanner.

### Local OSV checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\os.ps1 -PlanOnly
.\tools\os.ps1
```

The existing PowerShell profile exposes this as `os` or `osv-scan`.
It runs the same recursive OSV-Scanner source scan included in `dc`, using this
repository as the root regardless of the caller's current directory. The scan
requires coverage of `gradle/verification-metadata.xml` and any existing module
`gradle.lockfile`. Generated `.worktrees`, `.deepsec`, and `caches` trees are
excluded. Reports are written to `reports/osv.txt` and `reports/osv.json`.
If `gradle/osv-scanner.toml` exists, a separate filtered comparison is written to
`reports/osv-filtered.txt` and `reports/osv-filtered.json`. The shared installation
supplies the pinned OSV-Scanner. `-PlanOnly` displays the scope without scanning.

### Local CodeQL checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\ql.ps1 -PlanOnly
.\tools\ql.ps1
```

The existing PowerShell profile exposes this as `ql` or `codeql-check`.
It checks the GitHub default branch's CodeQL workflow state and open CodeQL alerts
through GitHub CLI, and checks the current local Java/Kotlin and Gradle inputs.
Local analysis uses `:app:assembleDebug` from `config/android-check.json`.
A clean HEAD covered by a verified GitHub run, or unchanged inputs covered by a
validated local result, can reuse that result. Otherwise the runner installs the
pinned CodeQL bundle if needed and runs local analysis. Local results are not
uploaded. GitHub authentication is required for the remote checks; local CLI use
must satisfy the shared runner's repository and licensing checks.
Reports are written to `reports/codeql.txt` and, when local analysis runs,
`reports/codeql.sarif`. `-PlanOnly` shows the plan without GitHub API calls,
builds, or CodeQL analysis.

### Local Dependabot checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\db.ps1 -PlanOnly
.\tools\db.ps1
```

The existing PowerShell profile exposes this as `db` or `dependabot-check`.
It checks for `.github/dependabot.yml` and retrieves all open Dependabot alerts
through GitHub CLI. The included configuration schedules weekly Gradle and
GitHub Actions dependency updates at the repository root. The shared installation
supplies the pinned GitHub CLI; GitHub authentication and access to the
repository's alerts are required. Results are written to `reports/dependabot.txt`.
Authentication, permission, and API errors are reported separately from an empty
alert list. `-PlanOnly` displays the plan without contacting GitHub.

### Local duplicate code checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\pc.ps1 -PlanOnly
.\tools\pc.ps1
```

The existing PowerShell profile exposes this as `pc` or `pmd-check`.
It runs PMD CPD duplicate detection over Kotlin and Java files in the declared
`main`, `test`, and `androidTest` source directories. Build output is outside this
source scope. The shared installation supplies the pinned PMD tool; missing tools
are reported as errors. The combined report is written to `reports/pmd-cpd.txt`,
with separate reports for each scanned language. `-PlanOnly` shows the source
scope without running PMD.

### Local Compose stability checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\cs.ps1 -PlanOnly
```

The existing PowerShell profile exposes this as `cs` or `compose-stability`.
It runs `:app:stabilityCheck` through Skydoves Compose Stability Analyzer and
writes `reports/compose-stability.txt`. Test sources are not included implicitly.
The command never creates or updates a baseline automatically.

Before the first check, explicitly create a baseline (both variants compile first):

```powershell
.\gradlew.bat :app:stabilityDump --no-daemon
```

Review the generated files under `app/stability/`, then run `cs`. Keep those
baseline files with the project so future checks can detect changes. A missing
baseline is reported as a finding rather than a passing check. `cs` depends on
both variants' Kotlin compilations so it reads current compiler reports.
`-PlanOnly` shows the task without compiling or checking. The task dependencies
are explicit to avoid analyzer 0.15.0 eagerly creating unrelated AGP Compose
Preview tasks. Release unit tests are explicitly enabled because AGP's Preview
tasks require them even during the task discovery used by `bc`.

The analyzer's [Kotlin version map](https://skydoves.github.io/compose-stability-analyzer/version-map/)
lists analyzer 0.15.0 with Kotlin 2.4.20. This project retains Kotlin 2.4.21;
compiler compatibility must be checked when updating either tool.

### Local Compose rule checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\cr.ps1 -PlanOnly
.\tools\cr.ps1
```

The existing PowerShell profile exposes this as `cr` or `compose-rules`.
It runs mrmans0n Compose Rules through `:app:ktlintCheck` and `:app:detekt` in one
shared Gradle invocation. The Detekt rules use version 0.4.28 for Detekt 1.23.8;
the ktlint rules use version 0.6.8 for ktlint 1.8.0. The default Compose rules are
enabled in `config/detekt.yml`; opt-in rules keep their upstream defaults.
The same rules also run when those tasks are invoked through `lc`.
Reports are written to `reports/compose-rules-ktlint.txt` and
`reports/compose-rules-detekt.txt`, with the shared execution log in
`reports/compose-rules-gradle.txt`. `-PlanOnly` shows the tasks without running
the checks.

### Local Google Android security checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\ga.ps1 -PlanOnly
.\tools\ga.ps1
```

The existing PowerShell profile exposes this as `ga` or `google-android-security`.
It runs `:app:lintDebug` with Google's `com.android.security.lint:lint:1.0.4`
checks and writes the security report to `reports/google-android-security.txt`.
The rules also run through ordinary Android Lint and `lc` because they are
registered in the app's `lintChecks` configuration. The shared runner reads
the current run's lint evidence; missing or stale evidence is an error.
`-PlanOnly` shows the task without running Android Lint.

### Combined security checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\sc.ps1 -PlanOnly
.\tools\sc.ps1
```

The existing PowerShell profile exposes this as `sc` or `security-check`.
It combines Gradle dependency verification, OSV, OWASP Dependency-Check, the
secret scanners (working tree and reachable local Git history), Semgrep's
`p/kotlin` rules, and the project's `ac` checks. Android security checks are
included by default; `-Full` does not expand the current shared runner's scope.
`-WithoutDeps` skips dependency verification, OSV, and OWASP while retaining
the source and secret checks. DeepSec is a separate command.

Reports are written under `reports/`, including `semgrep-kotlin.txt`,
`security-summary.txt`, and `security-code.txt`, alongside the individual
check reports. `-PlanOnly` shows the scope without running the checks.

### Local build checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\bc.ps1 -PlanOnly
.\tools\bc.ps1
```

The existing PowerShell profile exposes this as `bc` or `build-check`.
It runs the build phase from `config/android-check.json`, currently
`:app:assembleDebug`, with a 20-minute timeout. This compiles and packages the
debug APK at `app/build/outputs/apk/debug/app-debug.apk`. The shared runner checks
that the declared Gradle task is available and returns the build phase result.
`-PlanOnly` shows the module, task, and timeout without invoking Gradle.

### Local test checks

Using the same PowerShell 7 and shared `Android-check` installation:

```powershell
.\tools\tc.ps1 -PlanOnly
.\tools\tc.ps1
.\tools\tc.ps1 -Full -PlanOnly
.\tools\tc.ps1 -Full
```

The existing PowerShell profile exposes this as `tc` or `test-check`.
The default run executes `:app:testDebugUnitTest`. `-Full` adds
`:app:connectedDebugAndroidTest` and requires a connected Android device or
emulator. Both task lists are declared in `config/android-check.json` as
`modules[].tasks.test` and `modules[].tasks.deviceTest`; the test phase has a
20-minute timeout. `-PlanOnly` shows the selected tasks without running Gradle
or accessing a device. The shared runner must include manifest-based `-Full`
support; the local shared installation has been updated with that support.

Gradle writes test reports under `app/build/reports/`. The runner returns the
test phase result and reports failed Gradle execution as an error.

### SonarCloud analysis

The project is linked to `Insaner1980_duplicates-android` in the `insaner1980`
organization at `https://sonarcloud.io`. The project identity is configured in
`sonar-project.properties` and `.sonar-config.json`.

```powershell
sonar -PlanOnly
sonar
```

The existing PowerShell profile routes these commands to `tools/sonar.ps1`,
loads `SONAR_TOKEN` from Windows Credential Manager when needed, and supplies
the upload flag. A direct script call uses
`.\tools\sonar.ps1 -AllowExternalUpload` with `SONAR_TOKEN` in the environment.
Keep credentials outside the repository.

The Gradle Sonar task builds debug, runs JVM tests (including Robolectric), and
generates a JaCoCo XML report with `:app:createDebugUnitTestCoverageReport`.
Coverage is imported from `app/build/reports/coverage/test/debug/report.xml`.
This coverage comes from JVM tests; connected-device tests remain available
through `tc -Full`. The analysis uploads the local working tree and metadata to
the SonarCloud project's main branch and waits for its quality gate.
`-PlanOnly` displays this scope without building or uploading.

The execution log is written to `reports/sonar.txt`, with the token redacted.
Exit codes are 0 for a successful analysis and quality gate, 1 for a failed
quality gate, and 2 for a technical error. The default execution timeout is one
hour; `-GradleTimeoutSeconds` overrides it. To generate coverage locally, run:

```powershell
.\gradlew.bat :app:createDebugUnitTestCoverageReport --no-daemon
```

The general check commands and the project-specific `sonar` command are configured.

### Gradle commands

Run these commands from the repository root in PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Install it on a connected device or emulator with:

```powershell
.\gradlew.bat installDebug
```

Run the connected Android test with:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## Storage and notification access

Duplicates needs Android's special All files access because its core function compares ordinary user files across mounted shared storage. The app checks `Environment.isExternalStorageManager()`, opens the app-specific system settings page, falls back to the general All files access page when necessary, and rechecks access when the app resumes. A scan cannot start without this access.

The app requests notification permission before starting a scan on Android 13 and newer. A denial does not prevent the foreground scan. Android may hide its progress notification from the notification drawer while continuing to show the foreground service in Task Manager. When notifications are allowed, the ongoing notification shows the current phase and measurable progress, opens the Scanning screen, and provides a Stop action.

Only explicit user action enqueues a new scan. WorkManager runs one unique foreground `dataSync` scan at a time. The app defines no periodic scan or app-owned boot trigger. WorkManager can reschedule an unfinished persisted request after a process or device restart.

Every Back action on the Scanning screen returns to Home without canceling the work. If a scan cannot finish, the persisted session shows a specific recovery message for removed storage access, an unavailable volume, full storage, a database problem, or foreground-worker failure when that cause is known.

## Reviewed deletion

Scanning never deletes files. Deletion starts only from Review duplicates after the user selects copies. The default selection is deterministic, protects MediaStore favorites, prefers a valid copy in a primary user location, and keeps at least one readable matching copy in each group. The user can keep additional copies.

The in-app confirmation is enabled by default. Before deletion, every selected file is checked again. Changed files are rehashed and compared, missing or inaccessible files are not claimed as deleted, and deletion stops for a group when no valid unselected copy remains. MediaStore items use Android's system deletion authorization. Other writable files are removed individually and their absence is verified. After a successful direct path deletion, an available stored content URI is also removed from MediaStore on a best-effort basis to avoid a stale row. Partial success is reported accurately and Room totals are updated transactionally.

Before any file mutation, Room records the selected file identities in a pending-deletion journal. Results are checkpointed after each direct deletion and each approved MediaStore batch. Normal completion applies the final database state and removes the journal in one transaction. If the process ends during authorization or deletion, the next Activity result or safe resume reconciles the journal. Only a file confirmed absent is removed from saved results, and the live authorization callback is gated against duplicate resume handling.

Deletion is permanent. The journal repairs saved result state after interruption; it does not restore files. The app does not provide a recycle bin, file recovery, or fake Undo action.

## Privacy

- File contents and thumbnails are never stored in Room.
- Room stores scan metadata, paths, fingerprints, duplicate membership, selections, exclusions, and temporary pending-deletion records. DataStore stores user preferences.
- Android backup and device transfer are disabled for app data.
- The app does not declare `INTERNET` and does not send scan data anywhere.
- Opening a non-photo preview is a user action. The chosen local viewer receives a read-only `content://` URI, never a `file://` URI.

## Known Android and storage limitations

- All files access still cannot read another app's private storage. Duplicates deliberately excludes `Android/data`, `Android/obb`, its own internal, cache, external-files, and external-media directories, inaccessible system locations, symbolic links, recycle-bin directories, and zero-byte files. Other legitimate shared content under `Android/media` remains eligible.
- Only mounted shared volumes exposed by Android can be scanned. Read-only volumes can contribute readable candidates, but non-writable copies are not selected for deletion. A stale preference for a detached volume is ignored when another selected root is usable. A scan cannot start with no usable selected root, and losing a captured root or storage access during a scan fails staging while preserving the previous completed results.
- MediaStore-backed deletion can require a separate Android confirmation. Canceling it leaves those files in place. Requests are processed in batches of no more than 2,000 items.
- Photo preview is built in. Video, audio, and document preview depends on a compatible installed viewer. A stored MediaStore URI is checked before it is shared, and a stale URI is reported as missing. When no MediaStore URI was stored, a readable file on a mounted shared-storage volume is exposed directly through the app's non-exported, read-only preview provider with temporary read permission. The provider rejects paths outside that volume, Android/data, Android/obb, and the app's own Android/media directory. Files are not copied to preview cache.
- Android limits very long background work. `dataSync` foreground services have a shared six-hour background limit per 24 hours on apps targeting Android 15 or newer, and Android 16 or newer can count long-running WorkManager jobs against the app's job quota.
- Google Play applies a restricted-permission policy to apps that request `MANAGE_EXTERNAL_STORAGE`. This personal full-device utility requires that access for its core on-device search behavior.

Android platform references: [All files access](https://developer.android.com/training/data-storage/manage-all-files), [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), and [long-running WorkManager jobs](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

## Icon attribution

The interface uses Google Material Icons. Google makes Material Icons available under the [Apache License 2.0](https://github.com/google/material-design-icons/blob/master/LICENSE). See the [Material Icons guide](https://developers.google.com/fonts/docs/material_icons).
