# KKC Updater Agent Rollout

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
