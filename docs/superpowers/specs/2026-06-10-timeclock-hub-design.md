# Timeclock Hub Design
*2026-06-10*

## Context

The previous timeclock system attempted a 3-way sync between an SB-100, an RTC-1000, and a Flask hub. The sync logic was unworkable. The punch creation and editing logic was sound and will be reused. This design replaces the entire system with a single-responsibility hub: one Docker container that watches one RTC-1000, caches punch state, and lets Android tablets clock employees in and out via a simple REST API. The clock-in/out UI is added as a new tab inside the existing KKCSheetTracker Android app.

---

## System Overview

```
RTC-1000 ←───── HTTP scrape ─────→ Hub Server (Docker / TrueNAS Scale)
                                         │
                    ┌────────────────────┤
                    │                    │
              Tablet A                Tablet B
          (same VLAN, mDNS)       (VPN site, manual IP)
```

The hub is the single source of truth. Tablets never talk to the RTC-1000 directly.

---

## Hub Server

### Deployment

- Docker container, runs on TrueNAS Scale
- `network_mode: host` required so mDNS multicast reaches the LAN
- SQLite database on a named volume (survives container restarts)

### Configuration (all via environment variables / `.env`)

| Variable | Default | Purpose |
|---|---|---|
| `RTC_URL` | — | `http://192.168.11.149` — timeclock IP (changes, so env-only) |
| `RTC_USER` | `admin` | Login username |
| `RTC_PASS` | — | Login password |
| `POLL_INTERVAL` | `180` | Seconds between attendance polls |
| `HUB_PORT` | `8765` | Fixed port tablets connect to |

### File Layout

```
timeclock-hub/
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── app.py              ← Flask + APScheduler + zeroconf
├── rtc_client.py       ← sb100_client.py stripped to RTC-1000 only
├── models.py           ← Employee + PunchRecord (SQLAlchemy + SQLite)
├── requirements.txt
└── data/               ← volume mount point for timeclock.db
```

### Database Schema (minimal)

**Employee**
- `id` — integer PK
- `pin` — 3-digit payroll ID from RTC-1000 (`payroll_id` field)
- `name` — "Last, First"
- `display_name` — display name as stored on device
- `is_active` — bool

**PunchRecord**
- `id` — integer PK
- `employee_id` — FK → Employee
- `punch_in` — datetime
- `punch_out` — datetime (null if still clocked in)
- `duration_hours` — float (calculated on punch-out, stored)

### Polling (APScheduler)

Every `POLL_INTERVAL` seconds:
1. Call `rtc_client.get_attendance(today)` 
2. For each returned punch: find matching Employee by `emp_id` → upsert PunchRecord (15-minute merge window to deduplicate physical-clock punches)
3. Employee list refreshed once daily (or on demand)

Time normalization (`normalize_time`) reused from existing sync_service.py — handles `06:59a` vs `6:59a` format differences.

### REST API Endpoints

**`GET /health`**
Returns `{"status": "ok"}`. Used by Android mDNS discovery ping.

**`GET /api/employees`**
Returns cached employee list. Android fetches once on screen open for local PIN lookup.
```json
[{"pin": "101", "name": "Chris Tennent", "display_name": "Chris"}]
```

**`GET /api/status?pin=<pin>`**
Returns current punch state from cache (no RTC-1000 call — cache only, fast).
```json
{
  "found": true,
  "name": "Chris Tennent",
  "display_name": "Chris",
  "is_clocked_in": true,
  "clocked_in_since": "08:32 AM",
  "hours_today": 3.5
}
```

**`POST /api/punch`**  body: `{"pin": "101"}`
Server determines in vs out from current open punch state.
- If clocking IN: create PunchRecord, push punch to RTC-1000, return `action: "in"`
- If clocking OUT: close PunchRecord, push punch to RTC-1000, calculate duration, return `action: "out"` with `hours_worked`
```json
{"name": "Chris Tennent", "action": "out", "hours_worked": 4.5}
```

### mDNS Advertisement

Advertises `_timeclock._tcp.local.` on port 8765 via Python `zeroconf` library on startup. Service name: `KKC-Timeclock`.

---

## Android App — KKCSheetTracker

### Dependencies Added

- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP calls to hub
- `INTERNET` permission in `AndroidManifest.xml` (if not already present)

### New Files

**`data/TimecardServerConfig.kt`**
DataStore-backed config. Stores optional manual server IP. If set, mDNS is skipped entirely.

**`data/TimecardDiscovery.kt`**
NsdManager wrapper. Browses for `_timeclock._tcp` service. Returns server base URL (`http://<ip>:8765`) on discovery or throws `DiscoveryTimeoutException` after 1.5 seconds.

**`data/TimecardRepository.kt`**
OkHttp-backed. Methods:
- `suspend fun getEmployees(): List<EmployeeInfo>`
- `suspend fun getStatus(pin: String): PunchStatus`
- `suspend fun punch(pin: String): PunchResult`

**`ui/timecard/TimecardViewModel.kt`**
State: `serverUrl`, `employees`, `typedPin`, `matchedName`, `punchStatus`, `loading`, `result`.

Discovery sequence on init:
1. Read `TimecardServerConfig` — if manual IP present, set `serverUrl` and skip to step 4
2. Start `TimecardDiscovery` → show "Searching for timeclock server…"
3. On discovery: save URL, proceed; on timeout: show "Server not found. Set IP in Settings."
4. Fetch employee list from `GET /api/employees`, cache in memory

**`ui/timecard/TimecardScreen.kt`**
Composable screens:

*Searching state:* Spinner + "Searching for timeclock server…"

*Not found state:* Icon + "Server not found" + "Open Settings" button linking to server IP field

*PIN entry state (layout never shifts — all regions are fixed height):*

```
┌─────────────────────────────────┐  ← frosted glass card, fixed 144dp height
│   ●  ○  ○                      │  ← 3 dots, always visible. fill as digits typed
│   Chris Tennent                 │  ← name line: reserved, fades in on 3rd digit
│   CLOCKED OUT                   │  ← status line: reserved, updates in place
└─────────────────────────────────┘

  ┌────┐  ┌────┐  ┌────┐
  │  1 │  │  2 │  │  3 │         ← frosted glass buttons, lighter top border for depth
  │  4 │  │  5 │  │  6 │           22sp digits, 68dp tall, 13dp radius
  │  7 │  │  8 │  │  9 │
  │  ⌫ │  │  0 │  │    │         ← bottom-right cell empty/invisible
  └────┘  └────┘  └────┘

  ┌─────────────────────────────┐
  │        CLOCK IN             │  ← pill button, always visible, disabled until ready
  └─────────────────────────────┘    blue when clocking in, amber when clocking out
```

- Name shows only after all 3 digits entered (not partial — avoids wrong-name flicker)
- On 3rd digit: auto-call `GET /api/status`, name fades in, status + button update
- Unknown PIN: name shows "Unknown ID" at low opacity, status shows error, button stays disabled
- On action tap: button shows "Clocking in…" / "Clocking out…" briefly, then result

*Result display (auto-dismiss after 2.5 seconds, then reset to blank PIN):*
- Clock in: "Clocked in! Have a great shift." (status line, blue)
- Clock out: "You worked **4.5 hrs** today. See you!" (status line, green)
- Confirmation shown in the action button ("✓ Clocked In" / "✓ Clocked Out")

**Visual style:** No hardcoded colors — all surfaces use `MaterialTheme.colorScheme` tokens so the screen adapts to light and dark mode automatically, matching the rest of the app.

- Screen background: `MaterialTheme.colorScheme.background`
- Display card: `Surface` with `hazeEffect` (same `HazeState` as the rest of the screen) + `BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.25f))`; top border slightly lighter for depth via `outline.copy(0.4f)` on the top edge
- Numpad buttons: `Surface(tonalElevation = 4.dp, shadowElevation = 2.dp, shape = RoundedCornerShape(13.dp))` — tonal elevation gives the frosted raised look in both modes
- Status colors: use semantic `MaterialTheme.colorScheme.primary` (clocked in) and `MaterialTheme.colorScheme.tertiary` (clocked out) for status text; error state uses `MaterialTheme.colorScheme.error`
- Action button: standard `Button` (Clock In = `primary`, Clock Out = `tertiary` container, disabled = system default)

### Modified Files

**`app/build.gradle.kts`** — add OkHttp dependency

**`AndroidManifest.xml`** — add `INTERNET` permission

**`navigation/NavGraph.kt`** — HOURS tab routes to `TimecardScreen` instead of launching external `com.example.timecard` app

**`ui/settings/SettingsScreen.kt`** — add "Timeclock Server IP" text field (one field; leave blank to use mDNS auto-discovery, enter IP to skip mDNS)

---

## Discovery Logic (Android, final)

```
TimecardViewModel.init()
  └─ TimecardServerConfig.getManualIp()
       ├─ non-null → serverUrl = "http://{ip}:8765" → fetch employees → ready
       └─ null → TimecardDiscovery.discover(timeout=1.5s)
                   ├─ found → serverUrl = discovered URL → fetch employees → ready
                   └─ timeout → state = NOT_FOUND (show settings prompt)
```

---

## What Is Reused from Existing Timeclock API

| Existing file | What is reused |
|---|---|
| `backend/sb100_client.py` | `login()`, `get_employees()`, `get_attendance()`, `add_punch()`, all timeout constants, alert-handling notes |
| `backend/sync_service.py` | `normalize_time()`, `_parse_device_time()`, `times_within_window()`, punch merge window pattern |
| `backend/time_calculator.py` | Hours calculation logic |

The rest (multiuser, payroll, PTO, projections, SB-100 sync, 3-way reconciliation) is abandoned.

---

## Out of Scope

- SB-100 integration — dropped entirely
- Punch history/reports UI in the app — not in this iteration
- Admin punch editing via the app — not in this iteration
- Pay period management — not in this iteration
