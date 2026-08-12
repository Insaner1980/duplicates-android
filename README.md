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

The checked-in Gradle 9.7 wrapper downloads build dependencies when they are not already cached. That build-time download does not add network access to the installed app.

## Build and test

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
- Photo preview is built in. Video, audio, and document preview depends on a compatible installed viewer. A stored MediaStore URI is checked before it is shared, and a stale URI is reported as missing. When no MediaStore URI was stored, a readable shared-storage file is exposed directly through the app's non-exported FileProvider with temporary read permission; it is not copied to preview cache.
- Android limits very long background work. `dataSync` foreground services have a shared six-hour background limit per 24 hours on apps targeting Android 15 or newer, and Android 16 or newer can count long-running WorkManager jobs against the app's job quota.
- Google Play applies a restricted-permission policy to apps that request `MANAGE_EXTERNAL_STORAGE`. This personal full-device utility requires that access for its core on-device search behavior.

Android platform references: [All files access](https://developer.android.com/training/data-storage/manage-all-files), [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), and [long-running WorkManager jobs](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

## Icon attribution

The interface uses Google Material Icons. Google makes Material Icons available under the [Apache License 2.0](https://github.com/google/material-design-icons/blob/master/LICENSE). See the [Material Icons guide](https://developers.google.com/fonts/docs/material_icons).
