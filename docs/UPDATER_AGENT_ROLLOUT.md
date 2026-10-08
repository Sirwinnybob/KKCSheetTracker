# KKC Updater Agent Rollout

## Shared APK location (2026-10-08)

Sheet Tracker 8.7.11, Hours Tracker 3.11.3, Assimp 2.06, and VNC Cast 1.3.1 use
`<Ready Jobs>/.appupdates/apps/<packageName>/` for release APKs. Sheet Tracker's
Settings detects and installs updates for all four packages as of 8.7.12; Hours
Tracker, Assimp, and VNC Cast also resolve their own package folder. Each app still
validates the APK's actual package and version. This does not require updater-agent.

As of Sheet Tracker 8.7.13, Settings also offers **Install** for missing Assimp
(Room Viewer), Hours Tracker, and VNC Cast apps when a matching APK is available.
Installed apps receive an **Update** offer only for newer versions. An unexpected
package lookup failure skips that app until a later scan; it is not treated as a
missing installation. Each action uses Android's existing installer confirmation.

The release publishers are `KKCSheetTracker/deploy_update.ps1`,
`Hours Tracker/AndroidApp/deploy_release.ps1`, and
`Assimp/AssimpAndroid/deploy_update.ps1`, and `VNCCast/deploy-android.ps1`.
Each has a local copy of
`tools/publish_app_update.ps1`; keep these copies synchronized. The publisher
verifies SHA-256, publishes with temporary files and rename, serializes local
manifest writes with `.publish.lock`, and then deletes superseded APKs belonging
to the same package. Active artifacts for other channels/packages are preserved;
history for deleted artifacts is removed. A failed build, stale APK metadata, or
failed copy leaves previously published versions available.

For the transition, publishers also leave one current APK per app in `.Updates`
so older tablets can install the migration release. New readers prefer their
canonical package folder, including when a saved custom path points to `.Updates`.
Publish with `-NoLegacyCopy` once those tablets have migrated. Retire `.Updates`
only after confirming every tablet has received the migration builds for all apps.
Debug builds continue to use `.Testing_Updates`. Migration markers and tablet logs
already under `.appupdates` remain intact.

Verification: `tools/tests/PublishAppUpdate.Tests.ps1`,
`tools/tests/DeploymentScripts.Tests.ps1`, and each app's `UpdateDirectoriesTest`.
The folder resolver copies in the four apps must also be kept synchronized.

## 1) Enroll Device Owner

Factory-reset the tablet, install `com.kkc.updateragent`, then set device owner:

```bash
adb shell dpm set-device-owner com.kkc.updateragent/com.kkc.updateragent.admin.KkcDeviceAdminReceiver
```

## 2) Feed Layout

```
<Ready Jobs>/.appupdates/
  device_policy.json
  apps/
    manifest.json
    com.kkc.sheettracker/
      com.kkc.sheettracker-v3.2.3-3230.apk
  <tabletId>/
    install-log.ndjson
    updater-fallback-required.json (only when silent flow cannot proceed)
```

## 3) Publish Command

```powershell
.\deploy_update.ps1 `
  -ProjectPath "C:\Scripts\KKCSheetTracker" `
  -AppModule "app" `
  -PackageName "com.kkc.sheettracker" `
  -RolloutChannel "stable" `
  -FeedRoot "Y:\Ready Jobs\.appupdates\apps"
```

## 4) Contracts

- `manifest.json`: active release by package/channel (`apps`) + prior releases (`history`).
- `device_policy.json`: polling cadence, maintenance window, managed package list.
- `install-log.ndjson`: one JSON line per install decision/result with timestamp and error text.
