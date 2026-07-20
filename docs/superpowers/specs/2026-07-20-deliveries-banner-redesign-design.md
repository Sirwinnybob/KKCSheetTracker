# Deliveries Banner Redesign — Design Spec

## What & Why

The current `DeliveryScheduleWidget` (compact grid of 5 day columns × AM/PM, all crammed into one small always-visible row) is hard to read on a tablet — small text, no hierarchy, no indication of which day is "today." This redesign turns it into a collapsible, animated dropdown banner that's legible at a glance and expands to show full delivery detail inline, without needing the full-screen dialog just to look at the schedule.

Also included: the address field gains explicit support for raw coordinates (exact pin drop) alongside its existing free-text/Plus-Code behavior.

Scope is Android-only (`KKCSheetTracker`). No server/data-model changes — `DeliverySchedule`, `DeliveryJob`, `DELIVERY_DAYS`, etc. (`data/models/DeliveryScheduleModels.kt`) are unchanged. `DeliveryScheduleDialog.kt` is unchanged in implementation, but its role narrows to **admin editing only**.

---

## Interaction Model

### Collapsed (default)
A single header row, same visual language as `SectionProgressHeader` in `StatusComponents.kt` (4dp primary-color left accent bar, `Surface` with rounded corners, chevron toggle):

- Text: `"$totalDeliveries — Deliveries This Week"` where `totalDeliveries` = sum of `jobs.size` across all 10 slots.
- `Icons.Default.ArrowDropDown` at the trailing edge.
- Whole row is `clickable` → toggles banner expanded/collapsed.
- Admin only: a small edit icon (`Icons.Default.Edit` or similar) at the trailing edge, before the chevron, visible only when `isAdminMode == true`. Tapping it opens the existing `DeliveryScheduleDialog` — independent of the expand/collapse toggle, does not affect banner state.
- Hidden entirely when `schedule.isEmpty && !showWhenEmpty` — same rule `shouldShowDeliveryScheduleWidget` uses today (kept as-is).

### Expanded
Revealed below the header via `expandVertically` + `fadeIn` (banner-level open/close), matching the app's existing `NavSpringSize`-style spring used in `AppScaffold.kt` / `DeliveryScheduleDialog.kt`'s `DeliverySizeSpring`.

Inside: a horizontally-scrollable `Row` (`horizontalScroll`, only engages if content overflows) of 5 day segments, Mon→Fri, `Arrangement.spacedBy(KKCSpacing.xs)`:

- **Collapsed day segment** (no deliveries, or manually collapsed): a narrow ~48dp-wide chip. Label rotated 90° (`graphicsLayer { rotationZ = -90f }` on a `Text`, standard Compose vertical-label technique) reading `"$dayCount — $dayName"` (dayCount is almost always 0 here, but uses the same format even when nonzero after a manual collapse).
- **Expanded day segment**: width between `DeliveryMinColumnWidth` (140dp) and `DeliveryMaxColumnWidth` (260dp) — reuse the constants already defined in `DeliveryScheduleDialog.kt`. Shows:
  - Day header: `"$dayCount — $dayName"` (+ `" — Today"` suffix when it's the current day), bold, colored per the day-state rule below.
  - `AM` section label, then either job cards or italic `"No deliveries"` if the AM slot is empty.
  - `PM` section label, same treatment.
  - Each job card: job number (bold), description, and — only if `address.isNotBlank()` — a small `Icons.Default.LocationOn` icon button that fires `Intent(Intent.ACTION_VIEW, deliveryMapsUri(address))` (see Address Bar section below — same shared helper `DeliveryAddressActionsRow` uses). No copy-to-clipboard action here (that stays in the admin edit sheet inside the dialog) — this view is read-only.
- Width transition (collapsed chip ↔ expanded card) animates via `animateContentSize` (or `expandHorizontally`/`shrinkHorizontally` + the existing `DeliverySizeSpring`), consistent with the spring specs already in the codebase rather than introducing a new easing curve.

**Per-day expand state**: `remember { mutableStateOf<Set<String>>(...) }` seeded from `DELIVERY_DAYS.filter { day -> schedule has any job that day }` whenever `schedule` changes (`LaunchedEffect(schedule)` or `remember(schedule)`), but every segment — including empty ones — is independently tappable to toggle membership in that set. This lets someone collapse a busy day to scan others, or peek an empty day.

### Day state coloring (all days, both collapsed and expanded)
Computed once per composition from `LocalDate.now().dayOfWeek` compared against each day's index in `DELIVERY_DAYS`:

- `index < today` → greyed: `alpha = 0.4f`–`0.5f` on text/background.
- `index == today` → highlighted: primary-color border/background tint (mirrors the `WED` treatment in the approved mockup — 2dp primary border or primary container background).
- `index > today` → normal, full-opacity, no special tint.
- If today is Saturday or Sunday (not in `DELIVERY_DAYS`), no day gets the "today" highlight — all 5 days render greyed (the week's deliveries have already happened).

---

## Address Bar — Coordinates & Plus Codes

The address field (`DeliveryJob.address`) is free text today, and the maps launch always does `Uri.parse("geo:0,0?q=${URLEncoder.encode(address, "UTF-8")}")`. Plus Codes (e.g. `8FVC9G8V+5V` or `8FVC9G8V+5V Portland, OR`) already resolve correctly through that `q=` search — no code change needed there. Raw coordinates (e.g. `45.523, -122.676`) currently go through the same search path, which usually works but doesn't guarantee an exact pin/zoom. This adds explicit coordinate detection so those get a precise `geo:lat,lng` launch instead.

### New file: `ui/components/DeliveryAddressUtil.kt`
Shared by both `DeliveryScheduleDialog.kt` and `DeliveryScheduleBanner.kt` so the logic exists once.

```kotlin
fun parseDeliveryCoordinates(address: String): Pair<Double, Double>? {
    val parts = address.split(",").map { it.trim() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

fun deliveryMapsUri(address: String): Uri {
    val coords = parseDeliveryCoordinates(address)
    return if (coords != null) {
        Uri.parse("geo:${coords.first},${coords.second}?q=${coords.first},${coords.second}")
    } else {
        Uri.parse("geo:0,0?q=${URLEncoder.encode(address, "UTF-8")}")
    }
}
```

- A bare `"lat,lng"` string (both parts parse as `Double`, within valid ranges) → `geo:lat,lng?q=lat,lng`, which drops an exact pin and zooms there. Anything else (street address, Plus Code, Plus Code + locality, unparseable junk) → falls through to the existing `geo:0,0?q=<encoded text>` search behavior, unchanged from today.
- **Modified: `DeliveryScheduleDialog.kt`** — `DeliveryAddressActionsRow`'s "Open in Maps" button switches from its inline `URLEncoder.encode` + `geo:0,0?q=` construction to `context.startActivity(Intent(Intent.ACTION_VIEW, deliveryMapsUri(address)))`. The "Copy" button is untouched.
- **New banner's maps icon** (see below) uses the same `deliveryMapsUri(address)` helper.

### Field hint text
Both address entry points in `DeliveryScheduleDialog.kt` — `DeliveryJobDetailSheet`'s edit field and `DeliveryAddJobPanel`'s manual-add field — get their `OutlinedTextField` `label` changed from `"Address"` to `"Address, coordinates, or Plus Code"`. No input masking or validation on save; the field stays plain free text, detection only happens at maps-launch time.

---

## Component Changes

### New file: `ui/components/DeliveryScheduleBanner.kt`
Replaces `DeliveryScheduleWidget.kt` (delete the old file — nothing else references it once call sites are migrated). Rough signature:

```kotlin
@Composable
fun DeliveryScheduleBanner(
    schedule: DeliverySchedule,
    isAdminMode: Boolean,
    onEditRequested: () -> Unit,
    modifier: Modifier = Modifier,
    showWhenEmpty: Boolean = false
)
```

- `onEditRequested` replaces the old `onTap` — wired to the same `showScheduleDialog = true` state each screen already has.
- Internally owns: `bannerExpanded: Boolean`, `expandedDays: Set<String>` (both `remember`/`rememberSaveable` as appropriate — banner open/closed state is probably worth surviving rotation/nav via `rememberSaveable`; per-day set can be plain `remember` since it re-derives from `schedule` anyway).
- `shouldShowDeliveryScheduleWidget` helper moves here (or gets renamed `shouldShowDeliveryScheduleBanner`) — same logic, same tests updated.

### Unchanged: `ui/components/DeliveryScheduleDialog.kt`
No implementation changes. `isAdminMode` continues to gate the editing UI inside it. It's simply invoked from a different trigger now (edit icon instead of banner tap) and, per the earlier design decision, is never opened for non-admins anymore (previously non-admins could tap the widget to see the dialog in read-only mode — that path is now served by the banner's own expanded state instead).

### Modified: 4 call sites
`JobBrowserScreen.kt`, `AssemblyJobsScreen.kt`, `HardwoodsJobsScreen.kt`, `SpecialtyJobsScreen.kt` — all follow the identical existing pattern:

```kotlin
DeliveryScheduleBanner(
    schedule = deliverySchedule,
    isAdminMode = adminMode,
    onEditRequested = { showScheduleDialog = true },
    showWhenEmpty = adminMode,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
)
```

The existing `if (showScheduleDialog) { DeliveryScheduleDialog(...) }` block at each call site is untouched.

---

## Backwards Compatibility

- No data model changes — reads the same `DeliverySchedule` every call site already loads.
- Empty-schedule behavior (hide unless admin) is preserved exactly.
- Admin editing (add/remove/drag-drop jobs, reset all) is fully preserved — same dialog, same wiring, just reached via an explicit edit icon instead of any-tap.

---

## Verification

1. **Collapsed banner renders**: with a populated schedule, confirm header shows correct total count and accent-bar/chevron styling matches `SectionProgressHeader`'s visual language.
2. **Expand/collapse animation**: tap header → day strip reveals with spring/fade; tap again → collapses. No layout jump/flicker.
3. **Default per-day state**: days with ≥1 job start expanded (wide card); empty days start collapsed (thin rotated-label chip).
4. **Manual per-day toggle**: tap an empty day's thin chip → expands to show "No deliveries" for both AM and PM. Tap a busy day's card → collapses to thin chip.
5. **Today highlight**: with device date on a weekday, confirm that day (and only that day) gets the primary-color highlight; earlier weekdays are greyed; later weekdays are normal.
6. **Weekend edge case**: with device date on Sat/Sun (or force via test), confirm all 5 days render greyed, none highlighted.
7. **Overflow scroll**: with 3+ days expanded simultaneously (enough to overflow a tablet-width screen), confirm the day strip scrolls horizontally rather than clipping or overlapping.
8. **Maps icon**: job with a non-blank address shows the location icon; tapping it fires the `geo:` intent. Job without an address shows no icon.
9. **Coordinate detection**: address `"45.523, -122.676"` → maps intent launches with `geo:45.523,-122.676?q=45.523,-122.676` (exact pin). Address `"8FVC9G8V+5V Portland, OR"` (Plus Code) and a normal street address both still launch via `geo:0,0?q=<encoded text>`, unchanged from today. Malformed near-coordinate text (e.g. `"45.5, abc"`, out-of-range values) falls through to the search path, not a crash.
10. **Address field hints**: both the admin edit sheet and the manual-add panel in `DeliveryScheduleDialog.kt` show the updated `"Address, coordinates, or Plus Code"` label.
11. **Admin edit entry point**: `isAdminMode = true` shows the edit icon on the collapsed header; tapping it opens `DeliveryScheduleDialog` in admin mode, independent of banner expand/collapse state. `isAdminMode = false` shows no edit icon, and there is no way to reach `DeliveryScheduleDialog` from the banner.
12. **Empty schedule**: non-admin sees banner hidden entirely; admin sees banner with `"0 — Deliveries This Week"` and all 5 days collapsed by default.
13. **Regression — existing tests**: `DeliveryScheduleWidgetTest.kt` migrates to cover the renamed show/hide helper; `DeliveryScheduleRequestStoreTest.kt` and `DeliveryScheduleRepositoryTest.kt` are unaffected (no data-layer changes).
