# Delivery Schedule — Design Spec

## What & Why

Shop leads need to see the week's delivery schedule at a glance without leaving the job board. A compact always-visible widget sits above the job grid showing Mon–Fri × AM/PM slots with job numbers and names. Tapping it expands to a full-screen detail view with addresses and map links. Admins manage the schedule from `kkc-admin` using the same editing experience as the JOB BOARD system.

---

## Architecture

Two codebases are involved: `kkc-admin` (server + browser admin UI) and `KKCSheetTracker` (Android app).

### Data shape

```
DeliverySchedule = {
  schemaVersion: 1,
  slots: {
    "monday_am":    { jobs: [ { jobNumber, description, address? }, ... ] },
    "monday_pm":    { jobs: [...] },
    "tuesday_am":   { jobs: [...] },
    ...
    "friday_pm":    { jobs: [...] }
  }
}
```

Ten slots total (Mon–Fri × AM/PM). Max 3 jobs per slot enforced in the store. `address` is optional.

---

## kkc-admin — Server

### New file: `server/src/lib/deliveryScheduleStore.ts`

Follows the `rulesStore.ts` atomic write pattern but stores on the **network drive** (not server-local) so the Android app can read the file directly without HTTP.

- Storage path: `{getConfig().basePath}/.metadata/delivery_schedule.json`
  (e.g. `Y:\Ready Jobs\.metadata\delivery_schedule.json`)
- `readJson` / `writeJson` helpers (same pattern as `rulesStore.ts`)
- Exported functions:
  - `getSchedule(): DeliverySchedule` — reads file, returns empty slots on missing/corrupt
  - `setSlot(slotKey: string, jobs: DeliveryJob[]): DeliverySchedule` — validates slotKey is a known key, clamps jobs to max 3, writes and returns full updated schedule
  - `resetSchedule(): DeliverySchedule` — sets all 10 slots to `{ jobs: [] }`, writes and returns

Valid slot keys: `monday_am`, `monday_pm`, `tuesday_am`, `tuesday_pm`, `wednesday_am`, `wednesday_pm`, `thursday_am`, `thursday_pm`, `friday_am`, `friday_pm`.

### New file: `server/src/types.ts` additions

```ts
export interface DeliveryJob {
  jobNumber: string;
  description: string;
  address?: string;
}

export interface DeliverySlot {
  jobs: DeliveryJob[];
}

export interface DeliverySchedule {
  schemaVersion: number;
  slots: Record<string, DeliverySlot>;
}
```

### New file: `server/src/routes/deliverySchedule.ts`

Mounted at top level (not under `/api/jobs/:folder`).

- `GET /api/delivery-schedule` — returns `{ schedule: slots }` (the `slots` object from the store)
- `PUT /api/delivery-schedule` — body `{ slot: string, data: { jobs: DeliveryJob[] } }`. Validates slot key and job count (≤ 3). Returns `{ schedule: updatedSlots }`.
- `POST /api/delivery-schedule/reset` — clears all slots. Returns `{ schedule: clearedSlots }`.

No auth beyond what the server already applies. No per-job folder resolution.

### Modified: `server/src/index.ts`

```ts
import deliveryScheduleRouter from './routes/deliverySchedule';
app.use('/api/delivery-schedule', deliveryScheduleRouter);
```

Registered before the static file catch-all.

---

## kkc-admin — Client

### New file: `client/src/components/DeliveryScheduleView.tsx`

A full-page component (not a modal) that renders the editing UI. Direct port of the JOB BOARD's `DeliveryScheduleModal.jsx` adapted to React + Tailwind in this codebase's style.

Features (all matching the JOB BOARD):
- 5-day × 2-period grid (Mon–Fri columns, AM/PM rows)
- Per-slot inline editing: job number, description, address fields
- Add job (up to 3 per slot), remove job
- Drag-to-reorder between slots (HTML5 drag events, same logic as JOB BOARD)
- Touch/tap move mode: select a job, tap a destination slot
- Reset All button with confirmation dialog
- Fetches schedule on mount via `GET /api/delivery-schedule`
- Saves each slot change immediately via `PUT /api/delivery-schedule`

### Modified: `client/src/api.ts`

Add `deliverySchedule` object:
```ts
deliverySchedule: {
  get: () => fetch('/api/delivery-schedule').then(r => r.json()),
  updateSlot: (slot: string, jobs: DeliveryJob[]) =>
    fetch('/api/delivery-schedule', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slot, data: { jobs } }),
    }).then(r => r.json()),
  reset: () =>
    fetch('/api/delivery-schedule/reset', { method: 'POST' }).then(r => r.json()),
}
```

### Modified: `client/src/App.tsx`

Add a top-level **"Schedule"** navigation item to the header/nav bar (alongside any existing top-level nav). Clicking it shows `DeliveryScheduleView` as a full-page view, replacing the job-specific content area. A back/close control returns to the job view.

---

## Android App

### New file: `data/models/DeliveryScheduleModels.kt`

```kotlin
data class DeliveryJob(
    val jobNumber: String,
    val description: String,
    val address: String = ""
)

data class DeliverySlot(
    val jobs: List<DeliveryJob> = emptyList()
)

data class DeliverySchedule(
    val slots: Map<String, DeliverySlot> = emptyMap()
) {
    fun slot(day: String, period: String): DeliverySlot =
        slots["${day}_${period}"] ?: DeliverySlot()

    val isEmpty: Boolean get() = slots.values.all { it.jobs.isEmpty() }
}

val DELIVERY_DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday")
val DELIVERY_DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
val DELIVERY_PERIODS = listOf("am", "pm")
```

### New file: `data/DeliveryScheduleRepository.kt`

Reads directly from the network filesystem — same pattern as `SheetRipProgressStore.kt`. No HTTP calls needed.

- Constructor takes `baseDir: File` (the `basePath` already known to the scan coordinator)
- `fun fetchSchedule(): DeliverySchedule` — reads `File(baseDir, ".metadata/delivery_schedule.json")`, parses JSON via Gson. Returns `DeliverySchedule()` (empty) on missing file or any error.
- Parsing: reads the `slots` object, maps each key to `DeliverySlot(jobs = [...])`.
- Called on `Dispatchers.IO` by the caller.

### New file: `ui/components/DeliveryScheduleWidget.kt`

A `@Composable` that renders the compact always-visible schedule.

Parameters:
```kotlin
@Composable
fun DeliveryScheduleWidget(
    schedule: DeliverySchedule,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
)
```

Layout:
- Outer `Surface` with slight tonal elevation, `clickable { onTap() }`
- Small header row: `"DELIVERIES"` label in `labelSmall` typography, left-aligned
- Below the header: a `Row` of 5 equal-weight `Column` composables (Mon–Fri)
  - Each column: day label (e.g. `"Mon"`) in a small header, then 2 sub-rows for AM and PM
  - Each sub-row: `"AM"` / `"PM"` label + job entries as `Text` in `labelSmall`
  - Each job entry: `"${job.jobNumber} — ${job.description}"` truncated to 1 line
  - Empty slot: `"—"` in muted color
- Hidden entirely (`return`) if `schedule.isEmpty`

### New file: `ui/components/DeliveryScheduleDialog.kt`

A `@Composable` full-screen dialog for the detailed view.

Parameters:
```kotlin
@Composable
fun DeliveryScheduleDialog(
    schedule: DeliverySchedule,
    onDismiss: () -> Unit
)
```

Layout:
- Compose `Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false))`
- `Scaffold` with `TopAppBar` title `"This Week's Delivery Schedule"` and close `IconButton`
- Body: `LazyColumn` or a horizontally-scrollable `Row` of 5 day columns
  - Each day column: day label header, then AM and PM sections
  - Each section: `"AM"` / `"PM"` label, then one card per job showing:
    - Job number in `bodyMedium` bold
    - Description in `bodySmall`
    - If `address` is non-blank: a row with a map pin `IconButton` (launches `Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${encoded}"))`) and a copy `IconButton` (copies to clipboard via `ClipboardManager`)
  - Empty slot: italic `"No deliveries"` in muted color
- Read-only — no editing controls

### Modified: `ui/browser/JobBrowserScreen.kt`

**Data loading:**
```kotlin
// Re-read whenever the scan snapshot changes (same trigger used everywhere else)
val deliverySchedule = remember(scanState.snapshot.basePath, scanState.snapshot.generation) {
    deliveryScheduleRepository.fetchSchedule()
}
var showScheduleDialog by remember { mutableStateOf(false) }
```

`deliveryScheduleRepository` is constructed with `File(scanState.snapshot.basePath)` and passed in from the NavGraph call site, identical to how `jobRepository` is injected today.

**Widget placement:** Added at the top of the board view content area, directly above `JobBoardGrid`, inside the `AnimatedContent` block that already switches between board and list view. Only shown when in board view mode.

```kotlin
DeliveryScheduleWidget(
    schedule = deliverySchedule,
    onTap = { showScheduleDialog = true }
)
```

**Dialog:**
```kotlin
if (showScheduleDialog) {
    DeliveryScheduleDialog(
        schedule = deliverySchedule,
        onDismiss = { showScheduleDialog = false }
    )
}
```

`DeliveryScheduleRepository` is injected into `JobBrowserScreen` the same way `JobRepository` is currently injected — passed down from the NavGraph call site.

---

## Backwards Compatibility

- If `delivery_schedule.json` does not exist, `getSchedule()` returns an all-empty schedule. The widget hides itself. No crash.
- If `delivery_schedule.json` does not exist on the network drive yet (kkc-admin has never saved a schedule), `fetchSchedule()` returns an empty `DeliverySchedule` and the widget stays hidden. No crash or error.

---

## Verification

1. **kkc-admin — edit a slot:** Open Schedule view → click Edit on Monday AM → add a job (number + description + address) → Save. Confirm `data/delivery_schedule.json` contains the new entry.

2. **kkc-admin — reset:** Click Reset All → confirm dialog → confirm schedule clears to all-empty.

3. **Android widget — visible:** With at least one job in any slot, open the job board → confirm `DeliveryScheduleWidget` appears above the job grid showing the correct job number and name in the right day/period cell.

4. **Android widget — hidden when empty:** With all slots empty, confirm the widget does not appear.

5. **Android dialog — expand:** Tap the widget → `DeliveryScheduleDialog` opens full screen showing all slots. Job with an address shows map pin and copy icons.

6. **Android dialog — map intent:** Tap the map pin on a job with an address → Google Maps (or default maps app) opens with the address pre-filled.

7. **Android dialog — copy address:** Tap the copy icon → address is copied to clipboard.

8. **Android — live update:** Edit a slot in kkc-admin → on the next scan refresh the widget on the tablet reflects the change (driven by `scanState.snapshot.generation` changing).

9. **Android — missing file:** Delete `delivery_schedule.json` from the network drive → job board loads normally, widget is hidden, no crash or error toast.
