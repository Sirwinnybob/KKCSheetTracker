# Supply Subscriptions & Notifications Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow users to subscribe to specific supply items or entire categories on their tablet, displaying updates (comment, picture, note, status, label changes) in a default "Updates" tab and showing notification counts on the navbar Supply button.

**Architecture:** Introduce `SupplySubscriptionManager` to handle tablet-specific subscriptions and "last seen snapshots" stored in a local private JSON file (`supply_subscriptions.json` under internal files directory). Hook scanning into the app's `watcherRefreshEpoch` and compose screens, calculating update counts reactively without cross-device sync.

**Tech Stack:** Kotlin, Jetpack Compose, Gson, JUnit 4, Mockito

---

## User Review Required

> [!NOTE]
> **Syncthing & Cross-Device Tracking:** Any changes made on other tablets or servers are synchronized via Syncthing. The local `TrackerChangeMonitor` listens to these file changes and updates `watcherRefreshEpoch`. The subscription manager hooks into this epoch, meaning it will automatically detect and display changes made on other devices.
> **Swipe to Dismiss:** We will implement Material 3 `SwipeToDismissBox` on notification items inside the Updates tab to allow users to swipe-dismiss notifications.

## Proposed Changes

### Core Subscription Logic

#### [NEW] [SupplySubscriptionManager.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/SupplySubscriptionManager.kt)
Create the core subscription manager class that loads, saves, and tracks changes to items and categories.

#### [NEW] [SupplySubscriptionManagerTest.kt](file:///c:/Scripts/KKCSheetTracker/app/src/test/java/com/kkc/sheettracker/data/SupplySubscriptionManagerTest.kt)
Create tests verifying subscription toggles, notification scanning logic, and local file storage.

---

### Navigation and Integration

#### [MODIFY] [MainActivity.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/MainActivity.kt)
Instantiate `SupplySubscriptionManager` inside `onCreate`, then pass it down to `AppNavigation`.

#### [MODIFY] [NavGraph.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt)
Accept `supplySubscriptionManager` in `AppNavigation`, trigger regular scans on `watcherRefreshEpoch` changes, and pass it to `AppBottomNavBar` and `SupplyTabHost`.

---

### Navigation Bar Badging

#### [MODIFY] [AppScaffold.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt)
Add notification count parameter and display a BadgedBox around the navbar icon.

---

### Supply Tab UI Changes

#### [MODIFY] [SupplyItemDetailScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt)
Add subscription button (bell icon) in top bar, and mark item as read on load/resume.

#### [MODIFY] [SupplyDashboardScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt)
Modify screen to place an "Updates" tab at page index 0, followed by category tabs. Render category subscription toggles in the overflow menu. Implement swipe-to-dismiss for notifications.

---

## Verification Plan

### Automated Tests
*   Run `.\gradlew testDebugUnitTest --tests com.kkc.sheettracker.data.SupplySubscriptionManagerTest` to verify subscription persistence and change detection algorithms.

### Manual Verification
1.  **Subscription Action:** Navigate to a supply item detail screen. Tap the bell icon in the top right. Verify that the category/item lists update correctly.
2.  **Triggering changes:** Modify item status or add comments. Verify that the updates show up in the **Updates** tab.
3.  **Notification count badge:** Observe the supply icon badge on the bottom navbar increasing/decreasing dynamically.
4.  **Dismiss action:** Click the **Dismiss** button or **swipe left/right to dismiss** a notification in the Updates tab. Verify that the notification vanishes and the navbar badge decrements.
