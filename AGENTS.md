# KKCSheetTracker — Development Notes

## Project
Android app for KKC Custom Cabinets. Tracks sheet materials, jobs, and employee time.
Two shop locations connected via Omada site-to-site VPN.

## Timeclock Feature

### Architecture
- Hub server (`C:\Scripts\timeclock-hub\`) runs in Docker on TrueNAS Scale
- Polls one RTC-1000 device every 3 minutes; SQLite is the source of truth
- Android tablets talk to the hub via REST (mDNS auto-discovery or manual IP)
- Per-tablet background config stored in DataStore (`timeclock_background` prefs file)

### Frosted Glass Buttons — DO NOT use Surface + shadowElevation or semi-transparent background with shadow
Using `Surface(shadowElevation)` or `Modifier.shadow()` + `background(color.copy(alpha < 1f))` causes
the shadow to bleed through the transparent fill as a dark inner ring. This is a fundamental Android
hardware compositing issue — the compositor draws the shadow behind the layer and it shows through.

**The correct pattern for frosted semi-transparent elements:**
```kotlin
// hazeSource must be on the background layer (TimeclockBackground)
// Elements on top use hazeEffect — fills at full opacity so shadow cannot bleed through
modifier
    .shadow(elevation, shape, clip = false)   // external shadow only
    .clip(shape)
    .hazeEffect(state = hazeState, style = HazeDefaults.style(
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        blurRadius = 14.dp
    ))
```

For solid-color elements (action button): use `shadow(clip = false)` + `clip()` + `background(solidColor)`.
Never use `Surface(shadowElevation)` with any semi-transparent color — same bleed issue.

### hazeState wiring
`hazeState` lives in `TimecardScreen` and is applied to `TimeclockBackground` as `.hazeSource()`.
It flows down: `TimecardScreen` → `TimecardReadyState` → `NumpadGrid` → `NumpadKey`.
DisplayCard also receives it. Do NOT re-create a local hazeState inside `TimecardReadyState`.

### Hours display
Format with `"%.2f"` (two decimal places), never `"%.1f"`. Hub rounds up to nearest 15 minutes.

### Punch business rules
- Duration rounds UP to nearest 15-minute increment (`math.ceil(minutes / 15) * 15 / 60`)
- Punches under 7 minutes are deleted silently (accidental clock-in/out)
- Hub timezone: `TZ=America/Los_Angeles` in docker-compose — handles DST automatically

## Build
```
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Imported Claude Cowork project instructions
