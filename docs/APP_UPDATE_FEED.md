# KKC App Update Feed

Tablets update through each app's own in-app updater (Sheet Tracker's `UpdateManager`
also offers companion apps). The device-owner `updater-agent` app was removed on
2026-10-09; nothing here depends on it. Earlier history lived in this file as
`UPDATER_AGENT_ROLLOUT.md`.

## Shared APK location (2026-10-08)

Sheet Tracker 8.7.11, Hours Tracker 3.11.3, Assimp 2.06, and VNC Cast 1.3.1 use
`<Ready Jobs>/.appupdates/apps/<packageName>/` for release APKs. Sheet Tracker's
Settings detects and installs updates for all four packages as of 8.7.12; Hours
Tracker, Assimp, and VNC Cast also resolve their own package folder. Each app still
validates the APK's actual package and version.

As of Sheet Tracker 8.7.13, Settings also offers **Install** for missing Assimp
(Room Viewer), Hours Tracker, and VNC Cast apps when a matching APK is available.
Installed apps receive an **Update** offer only for newer versions. An unexpected
package lookup failure skips that app until a later scan; it is not treated as a
missing installation. Each action uses Android's existing installer confirmation.
Install offers for missing apps do not trigger the Settings notification dot;
the dot indicates updates for Sheet Tracker or an already installed companion app.
Settings lists missing apps in a separate **Available apps** card; they don't count
toward the updates chip or rail badge, and **Update All** skips them.
**Update All** installs one app at a time: each installer opens only after the
previous app reports its new version (checked when Settings returns to the
foreground), and Sheet Tracker is always last. A cancelled or failed install stops
the run.

The release publishers are `KKCSheetTracker/deploy_update.ps1`,
`Hours Tracker/AndroidApp/deploy_release.ps1`,
`Assimp/AssimpAndroid/deploy_update.ps1`, and `VNCCast/deploy-android.ps1`.
Each has a local copy of `tools/publish_app_update.ps1`; keep these copies
synchronized. Publishers now write only to
`.appupdates/apps/<packageName>/`. They verify SHA-256, publish with temporary
files and rename, serialize manifest writes with `.publish.lock`, and delete
superseded APKs belonging to the same package. Active artifacts for other
channels/packages are preserved; history for deleted artifacts is removed. A
failed build, stale APK metadata, or failed copy leaves previously published
versions available.

Legacy `.Updates` bridge publishing was retired on 2026-10-09 after confirmation
that all tablets have received the migration builds. Existing `.Updates` APKs are
left in place, and Android readers retain their fallback lookup for recovery.
Debug builds continue to use `.Testing_Updates`. Migration markers and tablet
logs already under `.appupdates` remain intact.

Verification: `tools/tests/PublishAppUpdate.Tests.ps1`,
`tools/tests/DeploymentScripts.Tests.ps1`, and each app's `UpdateDirectoriesTest`.
The folder resolver copies in the four apps must also be kept synchronized.

## Feed layout

```
<Ready Jobs>/.appupdates/
  apps/
    manifest.json
    com.kkc.sheettracker/
      com.kkc.sheettracker-v8.7.15-80715.apk
    com.example.timecard/ …
```

Files left over from the removed updater-agent (`device_policy.json`,
`<tabletId>/install-log.ndjson`, `updater-fallback-required.json`) have no reader
or writer and can be deleted.

## Publish command

```powershell
.\deploy_update.ps1   # defaults: app module, com.kkc.sheettracker, stable, Y:\Ready Jobs\.appupdates\apps
```

## Contracts

- `manifest.json`: active release by package/channel (`apps`) + prior releases (`history`).
