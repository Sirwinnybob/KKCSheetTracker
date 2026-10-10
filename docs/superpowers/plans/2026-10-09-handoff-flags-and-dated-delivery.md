# Handoff Flags + Dated Delivery Schedule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make KKCSheetTracker read, show and edit the two new Hours Tracker metadata contracts: dated delivery schedule (`delivery_schedule.json` schemaVersion 2) and per-item handoff flags (`handoff.json` `itemFlags`).

**Architecture:** Each contract gets a pure data layer (models, tolerant parser, pure display/week logic), a per-tablet request store (Mutex + atomic write + never silently overwrite), a small OkHttp client for the HT REST route, and one orchestrator that does "REST first / request file always or as fallback". UI changes are thin: they call the orchestrator and render pure-function output. Both features stay offline-safe: no tap waits on the network.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Gson + org.json, OkHttp 4.12 (+ MockWebServer in tests), kotlinx.coroutines `Mutex`, JUnit4. `java.time` for dates.

**Spec:** Two Hours Tracker handoff docs (both binding, both "additive only"):
- `C:\Scripts\Hours Tracker\docs\handoffs\2026-10-09-kkcsheettracker-dated-delivery-schedule.md` (Part A)
- `C:\Scripts\Hours Tracker\docs\handoffs\2026-10-08-kkcsheettracker-handoff-flags.md` (Part B)
- Authoritative schema: `C:\Scripts\Hours Tracker\METADATA_CONTRACT.md` §2.3, §2.12, §3.6. Read-only reference; never edit HT code.

**User decisions (2026-10-09):**
- Handoff flags shown on: Specialty item rows/details, Specialty kanban cards, Specialty sheet-rip rows, Hardwoods board-stock rows.
- Flag editing: **any station, any mode**, **`truck` only** (`count` is shown read-only). Editing disabled only on read-only (archive/view-only) screens.
- Delivery: week navigation (prev / this week / next) lives in the **DeliveryScheduleDialog top bar**; columns show real dates; **banner stays "this week" (`weekOf`)** with real dates. No separate calendar picker: placing = dropping into a dated column of the visible week. Admin dialog always shows Sat/Sun columns for dated (v2) schedules so weekend placement is possible; banner shows weekend days only when they hold jobs.
- Work lands **directly on `main`**. The 7 already-modified update/settings files in the working tree are NOT part of this work — never `git add -A`; stage only the files each task names.

## Global Constraints

- Additive only: do not rename or remove any existing JSON field, file, or reader. Keep v1 `slots` support.
- Tablet never writes `delivery_schedule.json`, `handoff.json`, `board_stock.json`, `specialty_items.json`, `checklist.json`.
- Shop zone: `ZoneId.of("America/Los_Angeles")` for "today". Never `LocalDate.now()` with device zone.
- Dated slot keys: `YYYY-MM-DD_am|pm`, lower-case. Weekday keys: `monday_am` … `friday_pm`.
- At most **3 jobs per half-day** slot.
- Never offer placement on dates more than **14 days** before shop-today.
- Every `requestedAt` is `Instant.now().toString()` (ISO-8601 UTC string). Never epoch millis, never zone-less local time.
- Request files: UTF-8 **without BOM** (Kotlin `writeText` default), written via `atomicWriteFile` (`data/AtomicFileWriter.kt`), under an in-process `Mutex`, one file per tablet. Never touch `*.rejected`. If an existing request file cannot be parsed, copy it to `<name>.unreadable-<epochMillis>` before starting a new one (that suffix matches neither HT's `*.json` globs nor KST's `tablet_items_*` glob).
- HT server URL: `AdminSyncConfig.getServerUrl()` (`http://<ip>:47821`). Folder names contain spaces: build URLs with `HttpUrl.Builder().addPathSegment(folder)`.
- HTTP: 5 s connect/read timeout, run on `Dispatchers.IO`, never block a tap.
- Handoff flag wire values are lower-case and case-sensitive (`loose`/`shop`/`later`, `qty`/`line`). Never write `enum.name`.
- Handoff flag key = `"<source>:<id>"`, split on the **first** colon only.
- Explicit JSON `null` for "back to Auto": use `org.json` `JSONObject.NULL` (Gson drops nulls).
- Handoff pending-overlay TTL: 120 s (contract, mirrors `SupplyLiveStateStore`).
- Delivery request-file poll on HT is 15 s; handoff sidecar pickup 30 s.
- Release version for this work: `versionName = "8.8.0"`, `versionCode = 80800` (from 8.7.15 / 80715).

## Review Focus

1. **v1 file / old HT server** — a tablet build with this plan talking to the current (un-upgraded) HT must behave exactly as today: weekday grid, no week nav, weekday slot keys, Saturday shows the week as past. Pinned by Task 1 (v1 parse), Task 3 (`v1 Saturday is all past`), Task 6 (`v1 schedule never uses PUT`).
2. **Saturday/Sunday rollover** — on a weekend `weekOf` is NEXT Monday; banner must show next week as future, never "all past". Pinned by Task 3 (`weekend today shows next week as future`).
3. **Two quick offline edits to the same half-day** — second whole-slot edit must not drop the first job. Editor builds from (live schedule + own pending request overlay). Pinned by Task 6 (`second offline place into same slot keeps first job`).
4. **Wrong checklist id** — checklist rows are `checklist:<id>` in KST but `checklist`/`<id>` in HT; HT silently accepts the wrong id. Pinned by Task 8 mapping tests.
5. **Handoff sidecar written while HT is consuming / unreadable sidecar** — append must preserve every earlier request, never overwrite a file it couldn't parse, and two fast taps both land. Pinned by Task 9 (`two concurrent taps both land`, `unreadable file is preserved`).

---

# Part A — Dated delivery schedule

## File map (Part A)

| File | Responsibility |
|---|---|
| `app/src/main/java/com/kkc/sheettracker/data/models/DeliveryScheduleModels.kt` (modify) | `DeliverySchedule` gains `datedSlots`, `weekOf`, `isDated`; slot-key helpers; `DeliveryScheduleFailure`, `DeliveryScheduleDocument` |
| `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt` (modify) | One parser `parseDeliveryScheduleDocument` for file, live frame, admin-sync POST, per-job PUT |
| `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleLiveClient.kt` (modify) | Accept v2 frames |
| `app/src/main/java/com/kkc/sheettracker/data/DeliveryWeek.kt` (create) | Pure week logic: shop today, `weekOf` rules, week view, day state, navigation bounds, pending overlay |
| `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRequestStore.kt` (modify) | Mutex, validation, dated keys, unreadable-file backup, `readPending` |
| `app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt` (modify) | `applyDeliveryScheduleEdits` (with failures), `setDeliveryJobDate` (per-job PUT) |
| `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleEditor.kt` (create) | Orchestrates place / remove / replace / reset: PUT → POST → request file; failure notices |
| `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt` (modify) | Week view of `weekOf`, real dates, weekend-safe day state |
| `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt` (modify) | Week nav, dated columns, semantic edit callbacks, notices, pending markers |
| `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt` (modify) | Wire editor, notices, pending overlay |

---

### Task 1: v2 schedule model + one tolerant parser

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/DeliveryScheduleModels.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleRepositoryTest.kt` (add tests; keep existing ones passing)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `DeliverySchedule(slots: Map<String, DeliverySlot>, datedSlots: Map<String, DeliverySlot> = emptyMap(), weekOf: LocalDate? = null, isDated: Boolean = false)` with `slot(day, period)`, `slotByKey(key): DeliverySlot`, `withSlot(key, jobs): DeliverySchedule`, `isEmpty`.
  - `data class DatedSlotKey(val date: LocalDate, val period: String)`; `fun parseDatedSlotKey(key: String): DatedSlotKey?`; `fun datedSlotKey(date: LocalDate, period: String): String`; `fun isWeekdaySlotKey(key: String): Boolean`.
  - `data class DeliveryScheduleFailure(slot: String, jobNumber: String, folderName: String, status: Int, reason: String)`
  - `data class DeliveryScheduleDocument(schedule: DeliverySchedule, failures: List<DeliveryScheduleFailure> = emptyList())`
  - `internal fun parseDeliveryScheduleDocument(json: String): DeliveryScheduleDocument` (throws on non-JSON, like today).
  - `internal fun parseDeliverySchedule(json: String): DeliverySchedule` (unchanged signature, delegates).

- [ ] **Step 1: Write the failing tests** — append to `DeliveryScheduleRepositoryTest.kt`:

```kotlin
    @Test
    fun parsesV2DocumentDatedSlotsWeekOfAndMirror() {
        val doc = parseDeliveryScheduleDocument(
            """
            {"schemaVersion":2,
             "datedSlots":{
               "2026-10-14_am":{"jobs":[{"jobNumber":"670","description":"ESCHRICH","address":"1364 Victorian","folderName":"670 - ESCHRICH 1364 VICTORIAN"}]},
               "2026-10-18_am":{"jobs":[{"jobNumber":"702","description":"SMITH","folderName":"702 - SMITH"}]}
             },
             "weekOf":"2026-10-12",
             "slots":{"tuesday_am":{"jobs":[{"jobNumber":"670","description":"ESCHRICH","address":"1364 Victorian","folderName":"670 - ESCHRICH 1364 VICTORIAN"}]}}}
            """.trimIndent()
        )
        val s = doc.schedule
        assertTrue(s.isDated)
        assertEquals(java.time.LocalDate.of(2026, 10, 12), s.weekOf)
        assertEquals("670", s.slotByKey("2026-10-14_am").jobs.single().jobNumber)
        // Sunday delivery only exists in datedSlots.
        assertEquals("702", s.slotByKey("2026-10-18_am").jobs.single().jobNumber)
        assertEquals("", s.slotByKey("2026-10-18_am").jobs.single().address)
        assertEquals("670", s.slot("tuesday", "am").jobs.single().jobNumber)
        assertTrue(doc.failures.isEmpty())
    }

    @Test
    fun v1DocumentStillParsesIntoWeekdayGrid() {
        val s = parseDeliverySchedule("""{"schemaVersion":1,"slots":{"monday_pm":{"jobs":[{"jobNumber":"1","description":"One"}]}}}""")
        assertFalse(s.isDated)
        assertNull(s.weekOf)
        assertTrue(s.datedSlots.isEmpty())
        assertEquals(10, s.slots.size)
        assertEquals("1", s.slot("monday", "pm").jobs.single().jobNumber)
    }

    @Test
    fun badDatedKeyOrBadJobIsSkippedWithoutLosingOthers() {
        val s = parseDeliverySchedule(
            """
            {"schemaVersion":2,"weekOf":"2026-10-12","slots":{},
             "datedSlots":{
               "2026-02-30_am":{"jobs":[{"jobNumber":"9","description":"Bad date"}]},
               "Tuesday_AM":{"jobs":[{"jobNumber":"8","description":"Bad key"}]},
               "2026-10-15_noon":{"jobs":[]},
               "2026-10-15_pm":{"jobs":["not-an-object",{"jobNumber":"7","description":"Good"}]},
               "2026-10-16_am":"not-an-object"
             }}
            """.trimIndent()
        )
        assertEquals(setOf("2026-10-15_pm"), s.datedSlots.keys)
        assertEquals("7", s.slotByKey("2026-10-15_pm").jobs.single().jobNumber)
    }

    @Test
    fun adminSyncResponseFailuresAreParsed() {
        val doc = parseDeliveryScheduleDocument(
            """
            {"schemaVersion":2,"weekOf":"2026-10-12","slots":{},"datedSlots":{},
             "failures":[{"slot":"2026-10-21_pm","jobNumber":"670","folderName":"670 - ESCHRICH","status":409,"reason":"half-day is full"}]}
            """.trimIndent()
        )
        assertEquals(
            listOf(DeliveryScheduleFailure("2026-10-21_pm", "670", "670 - ESCHRICH", 409, "half-day is full")),
            doc.failures
        )
    }

    @Test
    fun getAndPutShapeUsesScheduleKeyAsMirror() {
        // GET/PUT /api/delivery-schedule returns {schedule: <slots mirror>, datedSlots, weekOf, failures}.
        val s = parseDeliverySchedule(
            """{"schedule":{"thursday_pm":{"jobs":[{"jobNumber":"670","description":"E","folderName":"670 - E"}]}},
                "datedSlots":{"2026-10-15_pm":{"jobs":[{"jobNumber":"670","description":"E","folderName":"670 - E"}]}},
                "weekOf":"2026-10-12","failures":[]}"""
        )
        assertTrue(s.isDated)
        assertEquals("670", s.slot("thursday", "pm").jobs.single().jobNumber)
        assertEquals("670", s.slotByKey("2026-10-15_pm").jobs.single().jobNumber)
    }

    @Test
    fun weekOfThatIsNotAMondayIsIgnored() {
        val s = parseDeliverySchedule("""{"schemaVersion":2,"weekOf":"2026-10-14","datedSlots":{},"slots":{}}""")
        assertTrue(s.isDated)
        assertNull(s.weekOf)
    }

    @Test
    fun slotKeyHelpers() {
        assertEquals(DatedSlotKey(java.time.LocalDate.of(2026, 10, 21), "pm"), parseDatedSlotKey("2026-10-21_pm"))
        assertNull(parseDatedSlotKey("2026-10-21_PM"))
        assertNull(parseDatedSlotKey("2026-13-01_am"))
        assertEquals("2026-10-21_am", datedSlotKey(java.time.LocalDate.of(2026, 10, 21), "am"))
        assertTrue(isWeekdaySlotKey("friday_pm"))
        assertFalse(isWeekdaySlotKey("saturday_am"))
    }

    @Test
    fun withSlotRoutesDatedKeysToDatedSlots() {
        val job = com.kkc.sheettracker.data.models.DeliveryJob("1", "One", folderName = "1 - One")
        val s = DeliverySchedule(isDated = true)
            .withSlot("2026-10-21_pm", listOf(job))
            .withSlot("monday_am", listOf(job))
        assertEquals(listOf(job), s.datedSlots["2026-10-21_pm"]?.jobs)
        assertEquals(listOf(job), s.slots["monday_am"]?.jobs)
    }
```

Add imports at the top of the test file if missing: `com.kkc.sheettracker.data.models.DeliverySchedule`, `com.kkc.sheettracker.data.models.DeliveryScheduleFailure`, `com.kkc.sheettracker.data.models.DatedSlotKey`, `com.kkc.sheettracker.data.models.parseDatedSlotKey`, `com.kkc.sheettracker.data.models.datedSlotKey`, `com.kkc.sheettracker.data.models.isWeekdaySlotKey`, `org.junit.Assert.assertFalse`, `org.junit.Assert.assertNull`, `org.junit.Assert.assertTrue`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryScheduleRepositoryTest"`
Expected: FAIL — compilation errors (`parseDeliveryScheduleDocument`, `datedSlots`, `slotByKey` unresolved).

- [ ] **Step 3: Implement the model** — replace `DeliveryScheduleModels.kt` contents with:

```kotlin
package com.kkc.sheettracker.data.models

import java.time.LocalDate

data class DeliveryJob(
    val jobNumber: String = "",
    val description: String = "",
    val address: String = "",
    val folderName: String = ""
)

data class DeliverySlot(
    val jobs: List<DeliveryJob> = emptyList()
)

/**
 * Delivery schedule as published by Hours Tracker (`.metadata/delivery_schedule.json`).
 *
 * - [slots]: Monday-Friday mirror keyed `monday_am`..`friday_pm` (v1 shape, still written by v2).
 * - [datedSlots]: v2 canonical slots keyed `YYYY-MM-DD_am|pm` in the shop's local calendar,
 *   including weekend dates. Empty for a v1 document.
 * - [weekOf]: Monday of the week mirrored in [slots] (next Monday from Saturday 00:00 shop time).
 * - [isDated]: the document carried a `datedSlots` object (the server is v2-aware).
 */
data class DeliverySchedule(
    val slots: Map<String, DeliverySlot> = emptyMap(),
    val datedSlots: Map<String, DeliverySlot> = emptyMap(),
    val weekOf: LocalDate? = null,
    val isDated: Boolean = false
) {
    fun slot(day: String, period: String): DeliverySlot =
        slots["${day.lowercase()}_${period.lowercase()}"] ?: DeliverySlot()

    fun slotByKey(key: String): DeliverySlot =
        (if (parseDatedSlotKey(key) != null) datedSlots[key] else slots[key]) ?: DeliverySlot()

    fun withSlot(key: String, jobs: List<DeliveryJob>): DeliverySchedule =
        if (parseDatedSlotKey(key) != null) copy(datedSlots = datedSlots + (key to DeliverySlot(jobs)))
        else copy(slots = slots + (key to DeliverySlot(jobs)))

    val isEmpty: Boolean
        get() = slots.values.all { it.jobs.isEmpty() } && datedSlots.values.all { it.jobs.isEmpty() }
}

/** A job HT refused to place from an admin-sync batch (409 full, 404 folder missing, 400 closed job). */
data class DeliveryScheduleFailure(
    val slot: String,
    val jobNumber: String,
    val folderName: String,
    val status: Int,
    val reason: String
)

/** A parsed schedule document plus any per-job `failures` (admin-sync / per-job PUT responses only). */
data class DeliveryScheduleDocument(
    val schedule: DeliverySchedule,
    val failures: List<DeliveryScheduleFailure> = emptyList()
)

data class DeliverySchedulePickerJob(
    val folderName: String,
    val jobNumber: String,
    val description: String
)

data class DatedSlotKey(val date: LocalDate, val period: String)

private val DATED_SLOT_KEY = Regex("""^(\d{4}-\d{2}-\d{2})_(am|pm)$""")

/** Parses a lower-case `YYYY-MM-DD_am|pm` key; null for any other form or an impossible date. */
fun parseDatedSlotKey(key: String): DatedSlotKey? {
    val match = DATED_SLOT_KEY.matchEntire(key) ?: return null
    val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
    return DatedSlotKey(date, match.groupValues[2])
}

fun datedSlotKey(date: LocalDate, period: String): String = "${date}_${period.lowercase()}"

fun isWeekdaySlotKey(key: String): Boolean {
    val parts = key.split('_')
    return parts.size == 2 && parts[0] in DELIVERY_DAYS && parts[1] in DELIVERY_PERIODS
}

val DELIVERY_DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday")
val DELIVERY_DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
val DELIVERY_PERIODS = listOf("am", "pm")
```

- [ ] **Step 4: Implement the parser** — replace the top-level `parseDeliverySchedule` in `DeliveryScheduleRepository.kt` (keep `DeliveryScheduleRepository` class unchanged) with:

```kotlin
private val deliveryScheduleGson = GsonBuilder().create()

/**
 * Parses every delivery-schedule document shape with ONE function:
 * - on-disk master file and live WebSocket `schedule` payload: `{schemaVersion, slots, datedSlots?, weekOf?}`
 * - `POST /api/admin-sync/delivery-schedule` response: same plus `failures`
 * - `GET`/`PUT /api/delivery-schedule[/jobs/{folder}]`: mirror under `schedule` instead of `slots`
 *
 * v1 documents (no `datedSlots`) parse exactly as before. A bad key, bad date or bad job inside
 * one slot is skipped without discarding the rest. Throws only when [json] is not JSON at all,
 * so HTTP callers keep falling back on garbage bodies.
 */
internal fun parseDeliveryScheduleDocument(json: String): DeliveryScheduleDocument {
    val root = deliveryScheduleGson.fromJson(json, JsonObject::class.java)
        ?: return DeliveryScheduleDocument(DeliverySchedule())
    val mirror = root.objectOrNull("slots") ?: root.objectOrNull("schedule")
    val slots = if (mirror == null) emptyMap() else parseWeekdaySlots(mirror)
    val datedObj = root.objectOrNull("datedSlots")
    val datedSlots = if (datedObj == null) emptyMap() else parseDatedSlots(datedObj)
    val weekOf = root.get("weekOf")
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { LocalDate.parse(it.asString) }.getOrNull() }
        ?.takeIf { it.dayOfWeek == DayOfWeek.MONDAY }
    val schedule = DeliverySchedule(
        slots = slots,
        datedSlots = datedSlots,
        weekOf = if (datedObj != null) weekOf else null,
        isDated = datedObj != null
    )
    return DeliveryScheduleDocument(schedule, parseFailures(root))
}

internal fun parseDeliverySchedule(json: String): DeliverySchedule =
    parseDeliveryScheduleDocument(json).schedule

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun parseWeekdaySlots(slotsObj: JsonObject): Map<String, DeliverySlot> {
    val slots = mutableMapOf<String, DeliverySlot>()
    for (day in DELIVERY_DAYS) {
        for (period in DELIVERY_PERIODS) {
            val key = "${day}_${period}"
            slots[key] = DeliverySlot(jobs = parseJobs(slotsObj.objectOrNull(key)))
        }
    }
    return slots
}

private fun parseDatedSlots(datedObj: JsonObject): Map<String, DeliverySlot> =
    datedObj.entrySet().mapNotNull { (key, value) ->
        if (parseDatedSlotKey(key) == null || !value.isJsonObject) return@mapNotNull null
        key to DeliverySlot(jobs = parseJobs(value.asJsonObject))
    }.toMap()

private fun parseJobs(slotObj: JsonObject?): List<DeliveryJob> {
    val jobsArr = slotObj?.get("jobs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return jobsArr.mapNotNull { elem ->
        val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        DeliveryJob(
            jobNumber = obj.stringOrBlank("jobNumber"),
            description = obj.stringOrBlank("description"),
            address = obj.stringOrBlank("address"),
            folderName = obj.stringOrBlank("folderName")
        )
    }
}

private fun JsonObject.stringOrBlank(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

private fun parseFailures(root: JsonObject): List<DeliveryScheduleFailure> {
    val arr = root.get("failures")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { elem ->
        val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        DeliveryScheduleFailure(
            slot = obj.stringOrBlank("slot"),
            jobNumber = obj.stringOrBlank("jobNumber"),
            folderName = obj.stringOrBlank("folderName"),
            status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0,
            reason = obj.stringOrBlank("reason")
        )
    }
}
```

Update imports in `DeliveryScheduleRepository.kt`: add `com.kkc.sheettracker.data.models.DeliveryScheduleDocument`, `com.kkc.sheettracker.data.models.DeliveryScheduleFailure`, `com.kkc.sheettracker.data.models.parseDatedSlotKey`, `java.time.DayOfWeek`, `java.time.LocalDate`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryScheduleRepositoryTest"`
Expected: PASS (new and pre-existing tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/DeliveryScheduleModels.kt app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleRepositoryTest.kt
git commit -m "feat(delivery): parse v2 dated schedule, GET/PUT shape and failures with one parser"
```

---

### Task 2: Live socket accepts v2 frames

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleLiveClient.kt:172-175`
- Test: `app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleLiveClientTest.kt`

**Interfaces:**
- Consumes: `parseDeliverySchedule` (Task 1).
- Produces: v2 `snapshot`/`schedule` frames reach `onSchedule` with `isDated == true`.

- [ ] **Step 1: Write the failing test.** Open `DeliveryScheduleLiveClientTest.kt`, find the existing test that sends a valid `snapshot` frame (it builds an envelope `{"type":"snapshot","revision":…,"schedule":{…}}` and asserts `onSchedule` was called). Copy it as a new test named `acceptsV2SnapshotFrame` and change the frame's `schedule` object to:

```json
{"schemaVersion":2,"weekOf":"2026-10-12","slots":{},"datedSlots":{"2026-10-18_am":{"jobs":[{"jobNumber":"702","description":"SMITH","folderName":"702 - SMITH"}]}}}
```

Assert the delivered schedule has `isDated == true` and `slotByKey("2026-10-18_am").jobs.single().jobNumber == "702"`. Add a second copy `acceptsV2FrameWithoutSlotsObject` whose `schedule` omits `slots` entirely (`{"schemaVersion":2,"weekOf":"2026-10-12","datedSlots":{}}`) and assert it is delivered. Keep the existing test that rejects a frame whose schedule has neither `slots` nor `datedSlots` object (if absent, add one: `{"schemaVersion":1}` → `onSchedule` not called).

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryScheduleLiveClientTest"`
Expected: `acceptsV2FrameWithoutSlotsObject` FAILS (frame rejected).

- [ ] **Step 3: Implement** — replace `parseValidSchedule`:

```kotlin
    private fun parseValidSchedule(schedule: JsonObject): DeliverySchedule? {
        // v1 frames must carry a `slots` object; v2 frames carry `datedSlots` (and normally `slots`).
        val hasSlots = schedule.get("slots")?.isJsonObject == true
        val hasDatedSlots = schedule.get("datedSlots")?.isJsonObject == true
        if (!hasSlots && !hasDatedSlots) return null
        return runCatching { parseDeliverySchedule(gson.toJson(schedule)) }.getOrNull()
    }
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleLiveClient.kt app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleLiveClientTest.kt
git commit -m "feat(delivery): accept v2 schedule frames on the live socket"
```

---

### Task 3: Pure week logic

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/DeliveryWeek.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/DeliveryWeekTest.kt`

**Interfaces:**
- Consumes: Task 1 model + key helpers.
- Produces:
  - `val SHOP_ZONE: ZoneId`; `fun shopToday(clock: Clock = Clock.systemUTC()): LocalDate`
  - `fun mirrorWeekOf(today: LocalDate): LocalDate`
  - `fun defaultDeliveryWeekStart(schedule: DeliverySchedule, today: LocalDate): LocalDate`
  - `enum class DeliveryDayState { PAST, TODAY, FUTURE }` (moves here from `DeliveryScheduleBanner.kt`)
  - `data class DeliveryDayView(date, label, am, pm, amKey, pmKey, state, acceptsPlacement)` with `slot(period)`, `slotKey(period)`, `count`
  - `data class DeliveryWeekView(weekStart, days, isDefaultWeek, canGoBack, canGoForward)` with `totalCount`
  - `fun buildDeliveryWeek(schedule, weekStart, today, includeEmptyWeekend: Boolean): DeliveryWeekView`
  - `fun overlayPendingDeliveryRequest(schedule: DeliverySchedule, request: DeliveryScheduleEditRequest?): DeliverySchedule`
  - `fun pendingDeliverySlotKeys(request: DeliveryScheduleEditRequest?): Set<String>`
  - `const val MAX_DELIVERY_JOBS_PER_SLOT = 3`; `const val DELIVERY_HISTORY_DAYS = 14L`

- [ ] **Step 1: Write the failing tests** — create `DeliveryWeekTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class DeliveryWeekTest {
    private val mon = LocalDate.of(2026, 10, 12)
    private val wed = LocalDate.of(2026, 10, 14)
    private val sat = LocalDate.of(2026, 10, 17)
    private val sun = LocalDate.of(2026, 10, 18)
    private val nextMon = LocalDate.of(2026, 10, 19)
    private val job = DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH")

    private fun v2(weekOf: LocalDate, vararg dated: Pair<String, DeliveryJob>) = DeliverySchedule(
        slots = emptyMap(),
        datedSlots = dated.groupBy({ it.first }, { it.second }).mapValues { DeliverySlot(it.value) },
        weekOf = weekOf,
        isDated = true
    )

    @Test
    fun shopTodayUsesLosAngelesNotUtc() {
        // 2026-10-15T03:00Z is still Oct 14 in Oregon.
        val clock = Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"), java.time.ZoneOffset.UTC)
        assertEquals(wed, shopToday(clock))
    }

    @Test
    fun mirrorWeekOfFollowsSaturdayRollover() {
        assertEquals(mon, mirrorWeekOf(mon))
        assertEquals(mon, mirrorWeekOf(LocalDate.of(2026, 10, 16)))
        assertEquals(nextMon, mirrorWeekOf(sat))
        assertEquals(nextMon, mirrorWeekOf(sun))
    }

    @Test
    fun thisWeekFollowsWeekOf() {
        assertEquals(nextMon, defaultDeliveryWeekStart(v2(nextMon), today = wed))
    }

    @Test
    fun v2WithoutWeekOfFallsBackToMirrorRule() {
        assertEquals(nextMon, defaultDeliveryWeekStart(DeliverySchedule(isDated = true), today = sat))
    }

    @Test
    fun v1WeekIsTheWeekContainingToday() {
        assertEquals(mon, defaultDeliveryWeekStart(DeliverySchedule(), today = sat))
    }

    @Test
    fun realDatesMatchKeysAndLabels() {
        val week = buildDeliveryWeek(v2(mon, "2026-10-14_am" to job), mon, today = mon, includeEmptyWeekend = false)
        assertEquals(5, week.days.size)
        val tue = week.days[1]
        assertEquals(LocalDate.of(2026, 10, 13), tue.date)
        assertEquals("Tue 10/13", tue.label)
        assertEquals("2026-10-13_am", tue.amKey)
        assertEquals("2026-10-13_pm", tue.pmKey)
        assertEquals(listOf(job), week.days[2].am.jobs)
        assertEquals(1, week.totalCount)
    }

    @Test
    fun weekendDayShownOnlyWhenItHasJobsUnlessRequested() {
        val schedule = v2(mon, "2026-10-18_am" to job)
        val banner = buildDeliveryWeek(schedule, mon, today = mon, includeEmptyWeekend = false)
        assertEquals(listOf(12, 13, 14, 15, 16, 18), banner.days.map { it.date.dayOfMonth })
        val editor = buildDeliveryWeek(schedule, mon, today = mon, includeEmptyWeekend = true)
        assertEquals(7, editor.days.size)
    }

    @Test
    fun weekendTodayShowsNextWeekAsFuture() {
        listOf(sat, sun).forEach { today ->
            val schedule = v2(nextMon)
            val week = buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, today), today, includeEmptyWeekend = false)
            assertEquals(nextMon, week.weekStart)
            assertTrue(week.days.all { it.state == DeliveryDayState.FUTURE })
        }
    }

    @Test
    fun v1SaturdayIsAllPastLikeBefore() {
        val schedule = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(listOf(job))))
        val week = buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, sat), sat, includeEmptyWeekend = false)
        assertEquals("monday_am", week.days[0].amKey)
        assertEquals(listOf(job), week.days[0].am.jobs)
        assertTrue(week.days.all { it.state == DeliveryDayState.PAST })
        assertFalse(week.canGoBack)
        assertFalse(week.canGoForward)
    }

    @Test
    fun dayStateUsesDatesNotWeekdayIndex() {
        val week = buildDeliveryWeek(v2(mon), mon, today = wed, includeEmptyWeekend = false)
        assertEquals(
            listOf(DeliveryDayState.PAST, DeliveryDayState.PAST, DeliveryDayState.TODAY, DeliveryDayState.FUTURE, DeliveryDayState.FUTURE),
            week.days.map { it.state }
        )
    }

    @Test
    fun navigationForwardAndBackWithHistoryLimit() {
        val schedule = v2(mon)
        val thisWeek = buildDeliveryWeek(schedule, mon, today = wed, includeEmptyWeekend = false)
        assertTrue(thisWeek.isDefaultWeek)
        assertTrue(thisWeek.canGoBack)
        assertTrue(thisWeek.canGoForward)
        val prev = buildDeliveryWeek(schedule, mon.minusWeeks(1), today = wed, includeEmptyWeekend = false)
        assertFalse(prev.isDefaultWeek)
        // Cutoff is Sep 30. Week of Sep 28 (ends Oct 4) is still reachable from Oct 5's week...
        assertTrue(prev.canGoBack)
        // ...but week of Sep 21 (ends Sep 27) is not, so Sep 28's week can't go back.
        val twoBack = buildDeliveryWeek(schedule, mon.minusWeeks(2), today = wed, includeEmptyWeekend = false)
        assertFalse(twoBack.canGoBack)
        val next = buildDeliveryWeek(schedule, mon.plusWeeks(1), today = wed, includeEmptyWeekend = false)
        assertTrue(next.canGoForward)
    }

    @Test
    fun placementDisabledMoreThan14DaysBack() {
        val week = buildDeliveryWeek(v2(mon), mon.minusWeeks(2), today = wed, includeEmptyWeekend = false)
        // Sep 28..Oct 2; cutoff is Sep 30 (Oct 14 - 14 days).
        assertEquals(listOf(false, false, true, true, true), week.days.map { it.acceptsPlacement })
    }

    @Test
    fun pendingOverlayAppliesQueuedSlotEditsAndReset() {
        val other = DeliveryJob("702", "SMITH", folderName = "702 - SMITH")
        val schedule = v2(mon, "2026-10-14_am" to job, "2026-10-21_am" to other)
        val request = DeliveryScheduleEditRequest(
            tabletId = "T1",
            requestedAt = "2026-10-14T17:00:00Z",
            resetAll = true,
            slotEdits = listOf(DeliveryScheduleSlotEdit("2026-10-15_pm", listOf(job)))
        )
        val overlaid = overlayPendingDeliveryRequest(schedule, request)
        // resetAll clears weekOf..weekOf+4 only; future week kept; then slot edits applied.
        assertTrue(overlaid.slotByKey("2026-10-14_am").jobs.isEmpty())
        assertEquals(listOf(other), overlaid.slotByKey("2026-10-21_am").jobs)
        assertEquals(listOf(job), overlaid.slotByKey("2026-10-15_pm").jobs)
        assertEquals(setOf("2026-10-15_pm"), pendingDeliverySlotKeys(request))
        assertEquals(schedule, overlayPendingDeliveryRequest(schedule, null))
    }
}
```

Navigation rule: a week is reachable when its last day (weekStart + 6) is on or after `today - 14`, so `canGoBack` = "previous week's last day (weekStart − 1) ≥ cutoff".

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryWeekTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement** — create `DeliveryWeek.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.data.models.datedSlotKey
import com.kkc.sheettracker.data.models.isWeekdaySlotKey
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Hours Tracker keys every delivery date in the shop's calendar, never UTC or the tablet's zone. */
val SHOP_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

const val MAX_DELIVERY_JOBS_PER_SLOT = 3

/** HT drops dated slots older than this from the file, so placing there would make a job vanish. */
const val DELIVERY_HISTORY_DAYS = 14L

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE M/d", Locale.US)

fun shopToday(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(SHOP_ZONE))

/** HT's `weekOf` rule: this Monday on Mon-Fri, NEXT Monday from Saturday 00:00 through Sunday. */
fun mirrorWeekOf(today: LocalDate): LocalDate = when (today.dayOfWeek) {
    DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    else -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

/**
 * The week the UI opens on. v2: the file's `weekOf` (or HT's rule when missing). v1: the week
 * containing today, which reproduces the old behavior (on a weekend the whole week is past).
 */
fun defaultDeliveryWeekStart(schedule: DeliverySchedule, today: LocalDate): LocalDate =
    if (schedule.isDated) schedule.weekOf ?: mirrorWeekOf(today)
    else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

enum class DeliveryDayState { PAST, TODAY, FUTURE }

data class DeliveryDayView(
    val date: LocalDate,
    val label: String,
    val am: DeliverySlot,
    val pm: DeliverySlot,
    val amKey: String,
    val pmKey: String,
    val state: DeliveryDayState,
    val acceptsPlacement: Boolean
) {
    fun slot(period: String): DeliverySlot = if (period == "pm") pm else am
    fun slotKey(period: String): String = if (period == "pm") pmKey else amKey
    val count: Int get() = am.jobs.size + pm.jobs.size
}

data class DeliveryWeekView(
    val weekStart: LocalDate,
    val days: List<DeliveryDayView>,
    val isDefaultWeek: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean
) {
    val totalCount: Int get() = days.sumOf { it.count }
}

fun buildDeliveryWeek(
    schedule: DeliverySchedule,
    weekStart: LocalDate,
    today: LocalDate,
    includeEmptyWeekend: Boolean
): DeliveryWeekView {
    val historyCutoff = today.minusDays(DELIVERY_HISTORY_DAYS)
    val days = (0L until 7L).mapNotNull { offset ->
        val date = weekStart.plusDays(offset)
        val weekend = offset >= 5
        val amKey: String
        val pmKey: String
        if (schedule.isDated) {
            amKey = datedSlotKey(date, "am")
            pmKey = datedSlotKey(date, "pm")
        } else {
            if (weekend) return@mapNotNull null
            amKey = "${DELIVERY_DAYS[offset.toInt()]}_am"
            pmKey = "${DELIVERY_DAYS[offset.toInt()]}_pm"
        }
        val am = schedule.slotByKey(amKey)
        val pm = schedule.slotByKey(pmKey)
        if (weekend && !includeEmptyWeekend && am.jobs.isEmpty() && pm.jobs.isEmpty()) return@mapNotNull null
        DeliveryDayView(
            date = date,
            label = date.format(DAY_LABEL),
            am = am,
            pm = pm,
            amKey = amKey,
            pmKey = pmKey,
            state = when {
                date < today -> DeliveryDayState.PAST
                date == today -> DeliveryDayState.TODAY
                else -> DeliveryDayState.FUTURE
            },
            acceptsPlacement = !schedule.isDated || date >= historyCutoff
        )
    }
    return DeliveryWeekView(
        weekStart = weekStart,
        days = days,
        isDefaultWeek = weekStart == defaultDeliveryWeekStart(schedule, today),
        canGoBack = schedule.isDated && weekStart.minusDays(1) >= historyCutoff,
        canGoForward = schedule.isDated
    )
}

/**
 * Shows this tablet's own queued (not yet consumed) request on top of the published schedule.
 * HT applies `resetAll` first (clears the mirrored Mon-Fri week only), then each whole-slot edit.
 */
fun overlayPendingDeliveryRequest(
    schedule: DeliverySchedule,
    request: DeliveryScheduleEditRequest?
): DeliverySchedule {
    if (request == null) return schedule
    var result = schedule
    if (request.resetAll) {
        val weekOf = schedule.weekOf
        result = result.copy(
            slots = result.slots.mapValues { DeliverySlot() },
            datedSlots = if (weekOf == null) emptyMap() else result.datedSlots.filterKeys { key ->
                val date = parseDatedSlotKey(key)?.date ?: return@filterKeys true
                date < weekOf || date > weekOf.plusDays(4)
            }
        )
    }
    request.slotEdits.forEach { edit ->
        if (parseDatedSlotKey(edit.slot) != null || isWeekdaySlotKey(edit.slot)) {
            result = result.withSlot(edit.slot, edit.jobs)
        }
    }
    return result
}

fun pendingDeliverySlotKeys(request: DeliveryScheduleEditRequest?): Set<String> =
    request?.slotEdits?.map { it.slot }?.toSet().orEmpty()
```

Note: `canGoBack` is "previous week's last day (weekStart − 1) is on/after the cutoff", matching the rule in Step 1.

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/DeliveryWeek.kt app/src/test/java/com/kkc/sheettracker/data/DeliveryWeekTest.kt
git commit -m "feat(delivery): shop-zone week logic, weekOf rollover, pending overlay"
```

---

### Task 4: Request store — Mutex, validation, dated keys, no silent overwrite

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRequestStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleRequestStoreTest.kt` (wrap existing calls in `runBlocking`, add tests)

**Interfaces:**
- Consumes: Task 1 key helpers; `atomicWriteFile`; `MAX_DELIVERY_JOBS_PER_SLOT` (Task 3).
- Produces:
  - `interface DeliveryScheduleQueue { suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult; suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) }`
  - `data class DeliveryQueueResult(val queued: Boolean, val droppedJobs: List<DeliveryJob>)`
  - `class DeliveryScheduleRequestStore(baseDir: File) : DeliveryScheduleQueue` + `fun readPending(tabletId: String): DeliveryScheduleEditRequest?`
  - `DeliveryScheduleSlotEdit` / `DeliveryScheduleEditRequest` unchanged.

- [ ] **Step 1: Update existing tests** — in `DeliveryScheduleRequestStoreTest.kt` change each test to `= runBlocking { … }` (import `kotlinx.coroutines.runBlocking`), and change any `store.queueReset("tablet-1")` call to `store.queueReset("tablet-1", mirroredWeekOf = null)`.

- [ ] **Step 2: Add failing tests:**

```kotlin
    private fun requestJson(baseDir: File, tabletId: String = "tablet-1") =
        JsonParser.parseString(File(baseDir, "delivery_schedule_request.$tabletId.json").readText()).asJsonObject

    @Test
    fun datedKeysAreLowerCasedAndPassedThrough() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-dated").toFile()
        val result = DeliveryScheduleRequestStore(baseDir).queueSlotEdit(
            "2026-10-24_PM",
            listOf(DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH")),
            "tablet-1"
        )
        assertTrue(result.queued)
        val edit = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject
        assertEquals("2026-10-24_pm", edit.get("slot").asString)
    }

    @Test
    fun invalidSlotKeyIsNotWritten() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-badkey").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        listOf("2026-02-30_am", "saturday_am", "2026-10-24_noon", "").forEach { key ->
            assertFalse(store.queueSlotEdit(key, emptyList(), "tablet-1").queued)
        }
        assertFalse(File(baseDir, "delivery_schedule_request.tablet-1.json").exists())
    }

    @Test
    fun atMostThreeJobsAndInvalidJobsDropped() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-cap").toFile()
        val jobs = (1..4).map { DeliveryJob("$it", "Job $it", folderName = "$it - Job") } +
            DeliveryJob("", "No number", folderName = "x") +
            DeliveryJob("9", "", folderName = "y")
        val result = DeliveryScheduleRequestStore(baseDir).queueSlotEdit("2026-10-21_am", jobs, "tablet-1")
        assertEquals(listOf("4", "", "9"), result.droppedJobs.map { it.jobNumber })
        val written = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.getAsJsonArray("jobs")
        assertEquals(3, written.size())
    }

    @Test
    fun jobWithoutFolderNameIsNotQueuedAsPlaceableOnDatedKey() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-folder").toFile()
        val noFolder = DeliveryJob("670", "ESCHRICH")
        val result = DeliveryScheduleRequestStore(baseDir).queueSlotEdit("2026-10-21_am", listOf(noFolder), "tablet-1")
        assertEquals(listOf(noFolder), result.droppedJobs)
        val written = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.getAsJsonArray("jobs")
        assertEquals(0, written.size())
        // Weekday keys keep v1 behavior: folderName optional.
        val weekday = DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", listOf(noFolder), "tablet-1")
        assertTrue(weekday.droppedJobs.isEmpty())
    }

    @Test
    fun requestedAtIsIsoString() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-iso").toFile()
        DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        java.time.Instant.parse(requestJson(baseDir).get("requestedAt").asString)
    }

    @Test
    fun unreadableExistingFileIsPreservedNotSilentlyOverwritten() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-corrupt").toFile()
        val file = File(baseDir, "delivery_schedule_request.tablet-1.json")
        file.writeText("{not json")
        DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        val backups = baseDir.listFiles()!!.filter { it.name.startsWith("delivery_schedule_request.tablet-1.json.unreadable-") }
        assertEquals(1, backups.size)
        assertEquals("{not json", backups.single().readText())
        assertEquals("monday_am", requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.get("slot").asString)
    }

    @Test
    fun concurrentEditsBothLand() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-concurrent").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        kotlinx.coroutines.coroutineScope {
            (0 until 10).map { i ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    store.queueSlotEdit("2026-10-${10 + i}_am", emptyList(), "tablet-1")
                }
            }
        }
        assertEquals(10, requestJson(baseDir).getAsJsonArray("slotEdits").size())
    }

    @Test
    fun resetKeepsQueuedEditsOutsideTheMirroredWeek() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-reset").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        store.queueSlotEdit("2026-10-14_am", emptyList(), "tablet-1")
        store.queueSlotEdit("2026-10-21_am", emptyList(), "tablet-1")
        store.queueSlotEdit("tuesday_pm", emptyList(), "tablet-1")
        store.queueReset("tablet-1", mirroredWeekOf = java.time.LocalDate.of(2026, 10, 12))
        val root = requestJson(baseDir)
        assertTrue(root.get("resetAll").asBoolean)
        assertEquals(listOf("2026-10-21_am"), root.getAsJsonArray("slotEdits").map { it.asJsonObject.get("slot").asString })
    }

    @Test
    fun readPendingReturnsQueuedRequestOrNull() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-pending").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        assertNull(store.readPending("tablet-1"))
        store.queueSlotEdit("2026-10-21_am", emptyList(), "tablet-1")
        assertEquals("2026-10-21_am", store.readPending("tablet-1")!!.slotEdits.single().slot)
        File(baseDir, "delivery_schedule_request.tablet-1.json").writeText("{not json")
        assertNull(store.readPending("tablet-1"))
    }

    @Test
    fun rejectedFilesAreNeverTouched() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-rejected").toFile()
        val rejected = File(baseDir, "delivery_schedule_request.tablet-1.json.rejected").apply { writeText("old") }
        DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        assertEquals("old", rejected.readText())
    }
```

Imports to add: `org.junit.Assert.assertNull`, `kotlinx.coroutines.launch`.

- [ ] **Step 3: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryScheduleRequestStoreTest"`
Expected: FAIL (compile: `queueSlotEdit` not suspend / returns Unit, `readPending` missing).

- [ ] **Step 4: Implement** — replace the class body (keep the two data classes and update the KDoc to note L-06 is now fixed):

```kotlin
data class DeliveryQueueResult(val queued: Boolean, val droppedJobs: List<DeliveryJob>)

/** The offline fallback for delivery edits; [DeliveryScheduleRequestStore] is the production one. */
interface DeliveryScheduleQueue {
    suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult
    suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?)
}

/**
 * Queues tablet-authored schedule edits in `delivery_schedule_request.<tabletId>.json` at the Ready
 * Jobs root (METADATA_AUDIT.md M-04). HT polls every 15 s, applies files oldest-first by
 * `requestedAt`, deletes applied files and moves malformed ones to `<name>.rejected` (never touched
 * here). Validation below mirrors HT's rejection rules so a bad edit never poisons the whole file.
 *
 * Read-modify-write is serialized per file with an in-process Mutex (fixes METADATA_AUDIT.md L-06),
 * written atomically, and a file that can't be parsed is copied to `.unreadable-<millis>` before a
 * new one is started (never silently overwritten).
 */
class DeliveryScheduleRequestStore(private val baseDir: File) : DeliveryScheduleQueue {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun requestFile(tabletId: String) = File(baseDir, "delivery_schedule_request.$tabletId.json")

    override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult {
        val normalizedSlot = slot.trim().lowercase()
        val dated = parseDatedSlotKey(normalizedSlot) != null
        if (!dated && !isWeekdaySlotKey(normalizedSlot)) return DeliveryQueueResult(false, jobs)
        val valid = jobs.filter { job ->
            job.jobNumber.isNotBlank() && job.description.isNotBlank() && (!dated || job.folderName.isNotBlank())
        }
        val kept = valid.take(MAX_DELIVERY_JOBS_PER_SLOT)
        val dropped = jobs.filterNot { it in kept }
        lockFor(tabletId).withLock {
            val existing = readExisting(tabletId)
            writeRequest(
                tabletId,
                DeliveryScheduleEditRequest(
                    tabletId = tabletId,
                    requestedAt = Instant.now().toString(),
                    resetAll = existing?.resetAll ?: false,
                    slotEdits = existing?.slotEdits.orEmpty().filterNot { it.slot == normalizedSlot } +
                        DeliveryScheduleSlotEdit(slot = normalizedSlot, jobs = kept)
                )
            )
        }
        return DeliveryQueueResult(true, dropped)
    }

    override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) {
        lockFor(tabletId).withLock {
            val existing = readExisting(tabletId)
            // HT applies resetAll first, then slotEdits. Edits for slots the reset clears would
            // re-apply stale intent, so drop them; edits for other weeks / weekends survive.
            val survivors = existing?.slotEdits.orEmpty().filter { edit ->
                val date = parseDatedSlotKey(edit.slot)?.date ?: return@filter false
                mirroredWeekOf != null && (date < mirroredWeekOf || date > mirroredWeekOf.plusDays(4))
            }
            writeRequest(
                tabletId,
                DeliveryScheduleEditRequest(
                    tabletId = tabletId,
                    requestedAt = Instant.now().toString(),
                    resetAll = true,
                    slotEdits = survivors
                )
            )
        }
    }

    /** This tablet's queued request, or null when none is waiting (or it can't be read). */
    fun readPending(tabletId: String): DeliveryScheduleEditRequest? {
        val file = requestFile(tabletId)
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), DeliveryScheduleEditRequest::class.java) }
            .getOrNull()
            ?.takeIf { it.slotEdits != null }
    }

    /** Null when there is no file; backs up and returns null when the file can't be parsed. */
    private fun readExisting(tabletId: String): DeliveryScheduleEditRequest? {
        val file = requestFile(tabletId)
        if (!file.exists()) return null
        val parsed = runCatching { gson.fromJson(file.readText(), DeliveryScheduleEditRequest::class.java) }
            .getOrNull()
            ?.takeIf { it.slotEdits != null }
        if (parsed == null) {
            val backup = File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}")
            runCatching { file.copyTo(backup, overwrite = false) }
                .onFailure { Log.w(TAG, "Could not back up unreadable ${file.name}", it) }
            Log.w(TAG, "Unreadable ${file.name}; kept a copy as ${backup.name} and started a new request")
        }
        return parsed
    }

    private fun writeRequest(tabletId: String, payload: DeliveryScheduleEditRequest) {
        atomicWriteFile(requestFile(tabletId), gson.toJson(payload))
    }

    private fun lockFor(tabletId: String): Mutex =
        locks.getOrPut(requestFile(tabletId).absolutePath) { Mutex() }

    private companion object {
        const val TAG = "DeliveryRequestStore"
        /** Process-wide so two store instances for the same file still serialize. */
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
```

Imports: `android.util.Log`, `com.kkc.sheettracker.data.models.isWeekdaySlotKey`, `com.kkc.sheettracker.data.models.parseDatedSlotKey`, `kotlinx.coroutines.sync.Mutex`, `kotlinx.coroutines.sync.withLock`, `java.time.LocalDate`, `java.util.concurrent.ConcurrentHashMap`. (Gson can produce a `slotEdits` null for JSON lacking the field even though Kotlin types it non-null — hence `takeIf { it.slotEdits != null }`.)

- [ ] **Step 5: Fix the two production callers so the module compiles.** In `ui/jobs/UnifiedJobsScreen.kt` (`onQueueSlotEdit` / `onQueueReset` blocks near line 920-958) the calls are already inside `withContext(Dispatchers.IO)` in a coroutine: leave `deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)` as is (now suspend, return ignored) and change `deliveryScheduleRequestStore.queueReset(tabletId)` to `deliveryScheduleRequestStore.queueReset(tabletId, deliveryScheduleBinding.dialogSchedule.weekOf)`. Task 7 replaces this block entirely.

- [ ] **Step 6: Run to verify pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryScheduleRequestStoreTest"`
Expected: PASS. Then `.\gradlew.bat :app:compileDebugKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRequestStore.kt app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleRequestStoreTest.kt app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt
git commit -m "fix(delivery): lock, validate and never silently overwrite the request file; dated keys"
```

---

### Task 5: AdminSyncClient — failures + per-job PUT

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt`

**Interfaces:**
- Consumes: `parseDeliveryScheduleDocument` (Task 1).
- Produces:
  - `data class DeliveryScheduleApplyResult(val schedule: DeliverySchedule, val failures: List<DeliveryScheduleFailure>)`
  - `sealed interface DeliveryJobDateResult { data class Applied(val result: DeliveryScheduleApplyResult); data class Refused(val status: Int, val reason: String); object Unavailable }`
  - `suspend fun AdminSyncClient.applyDeliveryScheduleEdits(editRequest): DeliveryScheduleApplyResult?` (null = fall back)
  - `suspend fun AdminSyncClient.applyDeliverySchedule(editRequest): DeliverySchedule?` (kept, delegates)
  - `suspend fun AdminSyncClient.setDeliveryJobDate(folderName: String, date: LocalDate?, window: String?): DeliveryJobDateResult`

- [ ] **Step 1: Write failing tests** (append to `AdminSyncClientTest`):

```kotlin
    @Test
    fun `applyDeliveryScheduleEdits surfaces failures`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"schemaVersion":2,"weekOf":"2026-10-12","slots":{},"datedSlots":{},
                "failures":[{"slot":"2026-10-21_pm","jobNumber":"670","folderName":"670 - E","status":409,"reason":"full"}]}"""
        ))
        val result = client.applyDeliveryScheduleEdits(
            DeliveryScheduleEditRequest("T1", "2026-10-09T17:00:00Z", slotEdits = listOf(
                DeliveryScheduleSlotEdit("2026-10-21_pm", listOf(com.kkc.sheettracker.data.models.DeliveryJob("670", "E", folderName = "670 - E")))
            ))
        )
        assertEquals(409, result!!.failures.single().status)
        assertTrue(result.schedule.isDated)
        val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("2026-10-21_pm", body.getJSONArray("slotEdits").getJSONObject(0).getString("slot"))
    }

    @Test
    fun `applyDeliveryScheduleEdits returns null on 503 so caller falls back to request file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(client.applyDeliveryScheduleEdits(DeliveryScheduleEditRequest("T1", "2026-10-09T17:00:00Z")))
    }

    @Test
    fun `setDeliveryJobDate puts date and window with encoded folder`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"schedule":{},"datedSlots":{"2026-10-21_pm":{"jobs":[{"jobNumber":"670","description":"E","folderName":"670 - ESCHRICH 1364 VICTORIAN"}]}},"weekOf":"2026-10-19","failures":[]}"""
        ))
        val result = client.setDeliveryJobDate("670 - ESCHRICH 1364 VICTORIAN", java.time.LocalDate.of(2026, 10, 21), "pm")
        assertTrue(result is DeliveryJobDateResult.Applied)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/delivery-schedule/jobs/670%20-%20ESCHRICH%201364%20VICTORIAN", recorded.path)
        val body = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals("2026-10-21", body.getString("date"))
        assertEquals("pm", body.getString("window"))
    }

    @Test
    fun `setDeliveryJobDate null date sends JSON null body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schedule":{},"datedSlots":{},"weekOf":"2026-10-12","failures":[]}"""))
        client.setDeliveryJobDate("670 - E", null, null)
        assertEquals("null", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `setDeliveryJobDate maps refusals and unavailability`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Fri 10/16 AM already has 3 deliveries"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Folder not found"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Job is closed"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        val d = java.time.LocalDate.of(2026, 10, 16)
        assertEquals(DeliveryJobDateResult.Refused(409, "Fri 10/16 AM already has 3 deliveries"), client.setDeliveryJobDate("a", d, null))
        assertEquals(DeliveryJobDateResult.Refused(404, "Folder not found"), client.setDeliveryJobDate("a", d, null))
        assertEquals(DeliveryJobDateResult.Refused(400, "Job is closed"), client.setDeliveryJobDate("a", d, null))
        assertEquals(DeliveryJobDateResult.Unavailable, client.setDeliveryJobDate("a", d, null))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.AdminSyncClientTest"`
Expected: FAIL (unresolved).

- [ ] **Step 3: Implement.** Add top-level types above the class:

```kotlin
data class DeliveryScheduleApplyResult(
    val schedule: com.kkc.sheettracker.data.models.DeliverySchedule,
    val failures: List<com.kkc.sheettracker.data.models.DeliveryScheduleFailure>
)

sealed interface DeliveryJobDateResult {
    data class Applied(val result: DeliveryScheduleApplyResult) : DeliveryJobDateResult
    /** HT definitively refused this job (409 full, 404 folder unknown, 400/422 closed job or bad input). */
    data class Refused(val status: Int, val reason: String) : DeliveryJobDateResult
    /** 503, timeout, any other failure: retry via a slot edit / request file. */
    object Unavailable : DeliveryJobDateResult
}
```

Rename the body of the existing `applyDeliverySchedule` into `applyDeliveryScheduleEdits` returning `DeliveryScheduleApplyResult?` (response parsed with `parseDeliveryScheduleDocument(...)` → `DeliveryScheduleApplyResult(doc.schedule, doc.failures)`), and keep:

```kotlin
    /** Returns the canonical schedule on success, null on any failure (caller should fall back). */
    suspend fun applyDeliverySchedule(editRequest: DeliveryScheduleEditRequest): com.kkc.sheettracker.data.models.DeliverySchedule? =
        applyDeliveryScheduleEdits(editRequest)?.schedule
```

Add the per-job route:

```kotlin
    /**
     * `PUT /api/delivery-schedule/jobs/{folderName}` — changes only this one job's Office delivery
     * date, so it cannot clobber a job another tablet just placed. `date == null` clears it (JSON
     * `null` body). Only call against a v2 server (schedule.isDated): an old server has no route.
     */
    suspend fun setDeliveryJobDate(folderName: String, date: java.time.LocalDate?, window: String?): DeliveryJobDateResult =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("api/delivery-schedule/jobs")
                .addPathSegment(folderName)
                .build()
            val bodyText = if (date == null) "null" else JSONObject().apply {
                put("date", date.toString())
                if (window != null) put("window", window)
            }.toString()
            val request = Request.Builder().url(url).put(bodyText.toRequestBody(jsonMediaType)).build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val doc = parseDeliveryScheduleDocument(text)
                            DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(doc.schedule, doc.failures))
                        }
                        response.code in setOf(400, 404, 409, 422) -> DeliveryJobDateResult.Refused(
                            response.code,
                            runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
                        )
                        else -> DeliveryJobDateResult.Unavailable
                    }
                }
            }.getOrElse { DeliveryJobDateResult.Unavailable }
        }
```

Import `okhttp3.HttpUrl.Companion.toHttpUrl`.

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS (old tests included).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt
git commit -m "feat(delivery): admin-sync failures and per-job date PUT"
```

---

### Task 6: DeliveryScheduleEditor orchestrator

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleEditor.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleEditorTest.kt`

**Interfaces:**
- Consumes: Task 4 `DeliveryScheduleQueue`/`DeliveryQueueResult`; Task 5 result types; Task 1 model; Task 3 `MAX_DELIVERY_JOBS_PER_SLOT`.
- Produces:
  - `interface DeliveryScheduleRemote { suspend fun applyEdits(request: DeliveryScheduleEditRequest): DeliveryScheduleApplyResult?; suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?): DeliveryJobDateResult }`
  - `fun AdminSyncClient.asDeliveryRemote(): DeliveryScheduleRemote`
  - `sealed interface DeliveryEditOutcome { val notices: List<String> }` with `Applied(schedule, notices)`, `Queued(notices)`, `Refused(notices)`
  - `class DeliveryScheduleEditor(remote: DeliveryScheduleRemote?, queue: DeliveryScheduleQueue, tabletId: String, clock: Clock = Clock.systemUTC())` with `placeJob(current, job, toSlot, fromSlot)`, `removeJob(current, job, fromSlot)`, `replaceSlot(current, slotKey, jobs)`, `resetWeek(current)`.
  - `fun formatDeliveryFailure(f: DeliveryScheduleFailure): String`

`current` is ALWAYS the freshest schedule the UI has, with this tablet's pending request overlaid (Task 3 `overlayPendingDeliveryRequest`), so whole-slot fallbacks never drop a job queued a moment earlier.

- [ ] **Step 1: Write failing tests** — create `DeliveryScheduleEditorTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliveryScheduleFailure
import com.kkc.sheettracker.data.models.DeliverySlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliveryScheduleEditorTest {
    private val a = DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH")
    private val b = DeliveryJob("702", "SMITH", folderName = "702 - SMITH")
    private val mon = LocalDate.of(2026, 10, 12)
    private val v2 = DeliverySchedule(isDated = true, weekOf = mon)

    private class FakeRemote(
        var put: DeliveryJobDateResult = DeliveryJobDateResult.Unavailable,
        var post: DeliveryScheduleApplyResult? = null
    ) : DeliveryScheduleRemote {
        val puts = mutableListOf<Triple<String, LocalDate?, String?>>()
        val posts = mutableListOf<DeliveryScheduleEditRequest>()
        override suspend fun applyEdits(request: DeliveryScheduleEditRequest) = post.also { posts += request }
        override suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?) =
            put.also { puts += Triple(folderName, date, window) }
    }

    private class FakeQueue : DeliveryScheduleQueue {
        val edits = mutableListOf<Pair<String, List<DeliveryJob>>>()
        var resets = 0
        override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult {
            edits += slot to jobs
            return DeliveryQueueResult(true, emptyList())
        }
        override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) { resets++ }
    }

    @Test
    fun onlineDatedPlaceUsesPerJobPut() = runBlocking {
        val applied = DeliveryScheduleApplyResult(v2.withSlot("2026-10-21_pm", listOf(a)), emptyList())
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(applied))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(v2, a, "2026-10-21_pm", fromSlot = "2026-10-14_am")
        assertEquals(DeliveryEditOutcome.Applied(applied.schedule, emptyList()), outcome)
        assertEquals(listOf(Triple("670 - ESCHRICH", LocalDate.of(2026, 10, 21), "pm")), remote.puts)
        assertTrue(remote.posts.isEmpty())
        assertTrue(queue.edits.isEmpty())
    }

    @Test
    fun refusedPutIsReportedAndNotRetried() = runBlocking {
        val remote = FakeRemote(put = DeliveryJobDateResult.Refused(409, "Wed 10/21 PM already has 3 deliveries"))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(v2, a, "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Refused)
        assertEquals(listOf("Job 670 not placed: Wed 10/21 PM already has 3 deliveries"), outcome.notices)
        assertTrue(remote.posts.isEmpty() && queue.edits.isEmpty())
    }

    @Test
    fun putUnavailableFallsBackToSlotEditThenRequestFile() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(b))
        val remote = FakeRemote(put = DeliveryJobDateResult.Unavailable, post = null)
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(current, a, "2026-10-21_pm", "2026-10-14_am")
        assertTrue(outcome is DeliveryEditOutcome.Queued)
        // Dated: only the destination slot is sent (the job's one date moves it).
        assertEquals(listOf("2026-10-21_pm" to listOf(b, a)), remote.posts.single().slotEdits.map { it.slot to it.jobs })
        assertEquals(listOf("2026-10-21_pm" to listOf(b, a)), queue.edits)
    }

    @Test
    fun adminSyncFailuresBecomeNotices() = runBlocking {
        val failure = DeliveryScheduleFailure("2026-10-21_pm", "670", "670 - ESCHRICH", 409, "")
        val remote = FakeRemote(post = DeliveryScheduleApplyResult(v2, listOf(failure)))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").replaceSlot(v2, "2026-10-21_pm", listOf(a))
        assertEquals(listOf("Job 670 not placed: that half-day already has 3 deliveries"), outcome.notices)
    }

    @Test
    fun v1ScheduleNeverUsesPutAndMovesBothSlots() = runBlocking {
        val v1 = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(listOf(a)), "tuesday_pm" to DeliverySlot()))
        val remote = FakeRemote(put = DeliveryJobDateResult.Refused(404, "should not be called"), post = null)
        val queue = FakeQueue()
        DeliveryScheduleEditor(remote, queue, "T1").placeJob(v1, a, "tuesday_pm", "monday_am")
        assertTrue(remote.puts.isEmpty())
        assertEquals(setOf("tuesday_pm" to listOf(a), "monday_am" to emptyList<DeliveryJob>()), queue.edits.toSet())
    }

    @Test
    fun fullHalfDayRefusedLocally() = runBlocking {
        val full = v2.withSlot("2026-10-21_pm", listOf(b, b.copy(jobNumber = "703", folderName = "703 - X"), b.copy(jobNumber = "704", folderName = "704 - Y")))
        val remote = FakeRemote()
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(full, a, "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Refused)
        assertTrue(remote.puts.isEmpty())
    }

    @Test
    fun datedPlaceWithoutFolderRefused() = runBlocking {
        val outcome = DeliveryScheduleEditor(FakeRemote(), FakeQueue(), "T1").placeJob(v2, a.copy(folderName = ""), "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Refused)
    }

    @Test
    fun secondOfflinePlaceIntoSameSlotKeepsFirstJob() = runBlocking {
        val queue = FakeQueue()
        val editor = DeliveryScheduleEditor(remote = null, queue = queue, tabletId = "T1")
        editor.placeJob(v2, a, "2026-10-21_pm", null)
        // UI passes live schedule + own pending request overlaid.
        val pending = DeliveryScheduleEditRequest("T1", "x", slotEdits = queue.edits.map { DeliveryScheduleSlotEdit(it.first, it.second) })
        editor.placeJob(overlayPendingDeliveryRequest(v2, pending), b, "2026-10-21_pm", null)
        assertEquals(listOf(a, b), queue.edits.last().second)
    }

    @Test
    fun removeOnlineClearsDateOfflineSendsSlotWithoutJob() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a, b))
        val onlineRemote = FakeRemote(put = DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(v2, emptyList())))
        DeliveryScheduleEditor(onlineRemote, FakeQueue(), "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(Triple("670 - ESCHRICH", null as LocalDate?, null as String?), onlineRemote.puts.single())

        val queue = FakeQueue()
        DeliveryScheduleEditor(null, queue, "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(listOf("2026-10-21_pm" to listOf(b)), queue.edits)
    }

    @Test
    fun resetWeekPostsThenQueuesWithWeekOf() = runBlocking {
        val remote = FakeRemote(post = null)
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").resetWeek(v2)
        assertTrue(remote.posts.single().resetAll)
        assertEquals(1, queue.resets)
        assertTrue(outcome is DeliveryEditOutcome.Queued)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryScheduleEditorTest"`
Expected: FAIL (unresolved).

- [ ] **Step 3: Implement** — create `DeliveryScheduleEditor.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliveryScheduleFailure
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** The HT routes the editor needs; [AdminSyncClient] in production. */
interface DeliveryScheduleRemote {
    suspend fun applyEdits(request: DeliveryScheduleEditRequest): DeliveryScheduleApplyResult?
    suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?): DeliveryJobDateResult
}

fun AdminSyncClient.asDeliveryRemote(): DeliveryScheduleRemote = object : DeliveryScheduleRemote {
    override suspend fun applyEdits(request: DeliveryScheduleEditRequest) = applyDeliveryScheduleEdits(request)
    override suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?) =
        setDeliveryJobDate(folderName, date, window)
}

sealed interface DeliveryEditOutcome {
    val notices: List<String>
    /** HT confirmed; [schedule] is canonical and should replace the UI's copy. */
    data class Applied(val schedule: DeliverySchedule, override val notices: List<String>) : DeliveryEditOutcome
    /** Written to this tablet's request file; show as pending until a fresh read confirms it. */
    data class Queued(override val notices: List<String>) : DeliveryEditOutcome
    /** Nothing changed; tell the user why. */
    data class Refused(override val notices: List<String>) : DeliveryEditOutcome
}

fun formatDeliveryFailure(f: DeliveryScheduleFailure): String {
    val why = f.reason.ifBlank {
        when (f.status) {
            409 -> "that half-day already has 3 deliveries"
            404 -> "folder not found in Ready Jobs"
            400 -> "the office job is closed or invalid"
            else -> "refused by Hours Tracker (${f.status})"
        }
    }
    return "Job ${f.jobNumber.ifBlank { f.folderName }} not placed: $why"
}

/**
 * Turns a delivery-dialog gesture into the safest HT write available:
 * 1. v2 server + dated slot + job folder: per-job `PUT` (changes one job; can't clobber others).
 * 2. Otherwise (or PUT unavailable): whole-slot edit via `POST /api/admin-sync/delivery-schedule`.
 * 3. POST failed: this tablet's request file (HT polls every 15 s).
 *
 * [current] must be the freshest schedule with this tablet's pending request overlaid.
 */
class DeliveryScheduleEditor(
    private val remote: DeliveryScheduleRemote?,
    private val queue: DeliveryScheduleQueue,
    private val tabletId: String,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun placeJob(current: DeliverySchedule, job: DeliveryJob, toSlot: String, fromSlot: String?): DeliveryEditOutcome {
        val dest = current.slotByKey(toSlot).jobs
        if (dest.any { it.sameJob(job) }) return DeliveryEditOutcome.Applied(current, emptyList())
        if (dest.size >= MAX_DELIVERY_JOBS_PER_SLOT) {
            return DeliveryEditOutcome.Refused(listOf("Job ${job.jobNumber} not placed: that half-day already has 3 deliveries"))
        }
        val dated = parseDatedSlotKey(toSlot)
        if (dated != null && job.folderName.isBlank()) {
            return DeliveryEditOutcome.Refused(listOf("Job ${job.jobNumber} not placed: it has no Ready Jobs folder"))
        }
        if (dated != null && current.isDated && remote != null) {
            when (val r = remote.setJobDate(job.folderName, dated.date, dated.period)) {
                is DeliveryJobDateResult.Applied -> return applied(r.result)
                is DeliveryJobDateResult.Refused -> return refused(job, r)
                DeliveryJobDateResult.Unavailable -> Unit
            }
        }
        val edits = buildList {
            add(DeliveryScheduleSlotEdit(toSlot, dest + job))
            // v1 servers keep copies per slot, so a move must also empty the source slot. v2
            // jobs have one date: placing moves them, and resending the source could clobber.
            if (!current.isDated && fromSlot != null && fromSlot != toSlot) {
                add(DeliveryScheduleSlotEdit(fromSlot, current.slotByKey(fromSlot).jobs.filterNot { it.sameJob(job) }))
            }
        }
        return sendSlotEdits(edits)
    }

    suspend fun removeJob(current: DeliverySchedule, job: DeliveryJob, fromSlot: String): DeliveryEditOutcome {
        if (parseDatedSlotKey(fromSlot) != null && current.isDated && remote != null && job.folderName.isNotBlank()) {
            when (val r = remote.setJobDate(job.folderName, null, null)) {
                is DeliveryJobDateResult.Applied -> return applied(r.result)
                is DeliveryJobDateResult.Refused -> return refused(job, r)
                DeliveryJobDateResult.Unavailable -> Unit
            }
        }
        return sendSlotEdits(listOf(DeliveryScheduleSlotEdit(fromSlot, current.slotByKey(fromSlot).jobs.filterNot { it.sameJob(job) })))
    }

    /** Field edits inside one slot (job card edit). Always a whole-slot edit. */
    suspend fun replaceSlot(current: DeliverySchedule, slotKey: String, jobs: List<DeliveryJob>): DeliveryEditOutcome =
        sendSlotEdits(listOf(DeliveryScheduleSlotEdit(slotKey, jobs.take(MAX_DELIVERY_JOBS_PER_SLOT))))

    /** Clears every job dated in the mirrored Mon-Fri week (`weekOf`..`weekOf+4`). */
    suspend fun resetWeek(current: DeliverySchedule): DeliveryEditOutcome {
        val request = DeliveryScheduleEditRequest(tabletId, Instant.now(clock).toString(), resetAll = true)
        remote?.applyEdits(request)?.let { return applied(it) }
        queue.queueReset(tabletId, current.weekOf)
        return DeliveryEditOutcome.Queued(listOf("Reset queued; it applies when Hours Tracker picks it up."))
    }

    private suspend fun sendSlotEdits(edits: List<DeliveryScheduleSlotEdit>): DeliveryEditOutcome {
        val request = DeliveryScheduleEditRequest(tabletId, Instant.now(clock).toString(), slotEdits = edits)
        remote?.applyEdits(request)?.let { return applied(it) }
        val notices = mutableListOf<String>()
        edits.forEach { edit ->
            queue.queueSlotEdit(edit.slot, edit.jobs, tabletId).droppedJobs.forEach { dropped ->
                notices += "Job ${dropped.jobNumber.ifBlank { "(no number)" }} not queued: missing job number, name or folder, or the half-day is full"
            }
        }
        return DeliveryEditOutcome.Queued(notices)
    }

    private fun applied(result: DeliveryScheduleApplyResult) =
        DeliveryEditOutcome.Applied(result.schedule, result.failures.map(::formatDeliveryFailure))

    private fun refused(job: DeliveryJob, r: DeliveryJobDateResult.Refused) = DeliveryEditOutcome.Refused(
        listOf(formatDeliveryFailure(DeliveryScheduleFailure("", job.jobNumber, job.folderName, r.status, r.reason)))
    )
}

private fun DeliveryJob.sameJob(other: DeliveryJob): Boolean =
    if (folderName.isNotBlank() && other.folderName.isNotBlank()) folderName.equals(other.folderName, ignoreCase = true)
    else jobNumber == other.jobNumber
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleEditor.kt app/src/test/java/com/kkc/sheettracker/data/DeliveryScheduleEditorTest.kt
git commit -m "feat(delivery): editor routes edits PUT -> admin-sync -> request file with notices"
```

---

### Task 7: Banner + dialog + UnifiedJobsScreen wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt` (lines ~370-390 and ~918-958)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBannerTest.kt` (rewrite day-state tests)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/jobs/DeliveryScheduleWiringTest.kt` (create; source-scan)

**Interfaces:**
- Consumes: Tasks 1-6 (`buildDeliveryWeek`, `defaultDeliveryWeekStart`, `shopToday`, `DeliveryDayState`, `overlayPendingDeliveryRequest`, `pendingDeliverySlotKeys`, `DeliveryScheduleEditor`, `asDeliveryRemote`, `DeliveryEditOutcome`).
- Produces (banner, internal, tested):
  - `internal fun shouldShowDeliveryScheduleBanner(week: DeliveryWeekView, showWhenEmpty: Boolean): Boolean`
  - `internal fun daysWithDeliveries(week: DeliveryWeekView): Set<LocalDate>`
  - `internal fun deliveryBannerTitle(week: DeliveryWeekView, today: LocalDate): String`
- Produces (dialog signature):

```kotlin
fun DeliveryScheduleDialog(
    schedule: DeliverySchedule,
    onDismiss: () -> Unit,
    isAdminMode: Boolean = false,
    availableJobs: List<DeliverySchedulePickerJob> = emptyList(),
    onPlaceJob: ((job: DeliveryJob, toSlot: String, fromSlot: String?) -> Unit)? = null,
    onRemoveJob: ((job: DeliveryJob, fromSlot: String) -> Unit)? = null,
    onReplaceSlot: ((slot: String, jobs: List<DeliveryJob>) -> Unit)? = null,
    onResetWeek: (() -> Unit)? = null,
    notices: List<String> = emptyList(),
    onDismissNotices: () -> Unit = {},
    pendingSlotKeys: Set<String> = emptySet()
)
```

- [ ] **Step 1: Rewrite banner tests** — in `DeliveryScheduleBannerTest.kt` delete the `totalDeliveryCount_*`, `daysWithDeliveries_*`, `deliveryDayState_*` and `shouldShowDeliveryScheduleBanner_*` tests (their behavior moved to `DeliveryWeekTest`) and add:

```kotlin
    private val mon = java.time.LocalDate.of(2026, 10, 12)
    private val job = DeliveryJob("670", "E", folderName = "670 - E")

    private fun week(schedule: DeliverySchedule, today: java.time.LocalDate) =
        buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, today), today, includeEmptyWeekend = false)

    @Test
    fun bannerHiddenWhenWeekEmptyForNonAdminShownForAdmin() {
        val w = week(DeliverySchedule(isDated = true, weekOf = mon), mon)
        assertFalse(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false))
        assertTrue(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = true))
    }

    @Test
    fun bannerIgnoresJobsOutsideItsWeek() {
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-21_am", listOf(job))
        val w = week(s, mon)
        assertEquals(0, w.totalCount)
        assertFalse(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false))
    }

    @Test
    fun daysWithDeliveriesAreDates() {
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-14_pm", listOf(job))
        assertEquals(setOf(java.time.LocalDate.of(2026, 10, 14)), daysWithDeliveries(week(s, mon)))
    }

    @Test
    fun titleSaysNextWeekOnWeekendRollover() {
        val sat = java.time.LocalDate.of(2026, 10, 17)
        val s = DeliverySchedule(isDated = true, weekOf = mon.plusWeeks(1))
        assertEquals("Deliveries Next Week", deliveryBannerTitle(week(s, sat), sat))
        assertEquals("Deliveries This Week", deliveryBannerTitle(week(DeliverySchedule(isDated = true, weekOf = mon), mon), mon))
    }
```

Imports: `com.kkc.sheettracker.data.buildDeliveryWeek`, `com.kkc.sheettracker.data.defaultDeliveryWeekStart`, `com.kkc.sheettracker.data.models.DeliveryJob`, `org.junit.Assert.assertFalse`.

- [ ] **Step 2: Create wiring test** `app/src/test/java/com/kkc/sheettracker/ui/jobs/DeliveryScheduleWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.jobs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleWiringTest {
    @Test
    fun jobsScreenRoutesDialogEditsThroughTheEditor() {
        val src = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(src.contains("DeliveryScheduleEditor("))
        assertTrue(src.contains("asDeliveryRemote()"))
        assertTrue(src.contains("overlayPendingDeliveryRequest("))
        assertTrue(src.contains("onPlaceJob = "))
        assertTrue(src.contains("onRemoveJob = "))
        assertTrue(src.contains("onResetWeek = "))
        assertFalse(src.contains("onQueueSlotEdit = "))
    }

    @Test
    fun bannerAndDialogUseShopZoneNotDeviceZone() {
        listOf("ui/components/DeliveryScheduleBanner.kt", "ui/components/DeliveryScheduleDialog.kt").forEach { path ->
            val src = source(path)
            assertTrue(path, src.contains("shopToday("))
            assertFalse(path, src.contains("LocalDate.now()"))
        }
    }

    private fun source(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relativePath")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relativePath")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Missing source $relativePath")
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.DeliveryScheduleBannerTest" --tests "com.kkc.sheettracker.ui.jobs.DeliveryScheduleWiringTest"`
Expected: FAIL.

- [ ] **Step 4: Banner.** In `DeliveryScheduleBanner.kt`:
  1. Delete `totalDeliveryCount`, the old `daysWithDeliveries`, `enum class DeliveryDayState`, `deliveryDayState`, and the old `shouldShowDeliveryScheduleBanner`. Remove imports of `DELIVERY_DAYS`, `DELIVERY_PERIODS`, `java.time.DayOfWeek`, `java.time.LocalDate` (re-add `LocalDate` below).
  2. Add:

```kotlin
internal fun shouldShowDeliveryScheduleBanner(week: DeliveryWeekView, showWhenEmpty: Boolean): Boolean =
    showWhenEmpty || week.totalCount > 0

internal fun daysWithDeliveries(week: DeliveryWeekView): Set<LocalDate> =
    week.days.filter { it.count > 0 }.map { it.date }.toSet()

/** After HT's Saturday rollover `weekOf` is next Monday, so the banner is about next week. */
internal fun deliveryBannerTitle(week: DeliveryWeekView, today: LocalDate): String =
    if (week.weekStart > today) "Deliveries Next Week" else "Deliveries This Week"
```

  3. In `DeliveryScheduleBanner(...)` replace the body up to `val accentColor` with:

```kotlin
    val today = shopToday()
    val week = remember(schedule, today) {
        buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, today), today, includeEmptyWeekend = false)
    }
    if (!shouldShowDeliveryScheduleBanner(week, showWhenEmpty)) return

    var bannerExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedDays by remember { mutableStateOf(daysWithDeliveries(week)) }
    var previousDeliveryDays by remember { mutableStateOf(daysWithDeliveries(week)) }
    LaunchedEffect(week) {
        val currentDeliveryDays = daysWithDeliveries(week)
        expandedDays = expandedDays + (currentDeliveryDays - previousDeliveryDays)
        previousDeliveryDays = currentDeliveryDays
    }
```

  and the header text to `text = "${week.totalCount} — ${deliveryBannerTitle(week, today)}"`.
  4. `DeliveryDayStrip(week: DeliveryWeekView, expandedDays: Set<LocalDate>, onToggleDay: (LocalDate) -> Unit, onJobSelected)` iterates `week.days.forEach { day -> DeliveryDaySegment(day = day, isExpanded = day.date in expandedDays, onToggle = { onToggleDay(day.date) }, onJobSelected = onJobSelected) }`.
  5. `DeliveryDaySegment(day: DeliveryDayView, isExpanded, onToggle, onJobSelected)`: `state = day.state`, `dayCount = day.count`, label text `"${day.count} — ${day.label}"` (+ `" — Today"`), and period loop uses `day.slot(period)` for `DELIVERY_PERIODS` (keep the `DELIVERY_PERIODS` import for this loop). Rotated collapsed label: `"${day.count} — ${day.label}"`.
  6. Imports: `com.kkc.sheettracker.data.DeliveryDayState`, `com.kkc.sheettracker.data.DeliveryDayView`, `com.kkc.sheettracker.data.DeliveryWeekView`, `com.kkc.sheettracker.data.buildDeliveryWeek`, `com.kkc.sheettracker.data.defaultDeliveryWeekStart`, `com.kkc.sheettracker.data.shopToday`, `java.time.LocalDate`.

- [ ] **Step 5: Dialog.** In `DeliveryScheduleDialog.kt`:
  1. Change the signature to the one in **Interfaces** above. Delete the file-private `withSlot` extension and `MAX_DELIVERY_JOBS_PER_SLOT` constant (both now come from `DeliverySchedule.withSlot` and `com.kkc.sheettracker.data.MAX_DELIVERY_JOBS_PER_SLOT`).
  2. Week state, right after `val context = LocalContext.current`:

```kotlin
    val today = shopToday()
    var weekOffset by remember { mutableIntStateOf(0) }
    if (!schedule.isDated && weekOffset != 0) weekOffset = 0
    val weekStart = defaultDeliveryWeekStart(schedule, today).plusWeeks(weekOffset.toLong())
```

  and after `editableSchedule` is declared:

```kotlin
    val week = remember(editableSchedule, weekStart, today, isAdminMode) {
        buildDeliveryWeek(editableSchedule, weekStart, today, includeEmptyWeekend = isAdminMode && editableSchedule.isDated)
    }
    // Bounds are window coordinates of the visible week's cells; drop the old week's on navigation.
    LaunchedEffect(weekStart) { slotBounds.clear() }
```

  3. Replace `queueSlot`, `resetAll`, `moveJob` with:

```kotlin
    fun placeJob(job: DeliveryJob, toSlot: String, fromSlot: String?) {
        val dest = editableSchedule.slotByKey(toSlot).jobs
        if (dest.size >= MAX_DELIVERY_JOBS_PER_SLOT) return
        var next = editableSchedule
        if (fromSlot != null) next = next.withSlot(fromSlot, next.slotByKey(fromSlot).jobs.filterNot { it == job })
        editableSchedule = next.withSlot(toSlot, dest + job)
        onPlaceJob?.invoke(job, toSlot, fromSlot)
        pendingEdits += 1
    }

    fun removeJob(slotKey: String, index: Int) {
        val jobs = editableSchedule.slotByKey(slotKey).jobs
        val job = jobs.getOrNull(index) ?: return
        editableSchedule = editableSchedule.withSlot(slotKey, jobs.filterIndexed { i, _ -> i != index })
        onRemoveJob?.invoke(job, slotKey)
        pendingEdits += 1
    }

    fun replaceSlot(slotKey: String, jobs: List<DeliveryJob>) {
        val clipped = jobs.take(MAX_DELIVERY_JOBS_PER_SLOT)
        editableSchedule = editableSchedule.withSlot(slotKey, clipped)
        onReplaceSlot?.invoke(slotKey, clipped)
        pendingEdits += 1
    }

    fun resetWeek() {
        onResetWeek?.invoke()
        pendingEdits += 1
        confirmReset = false
        activeAddSlot = null
    }
```

  4. `endDrag()`: existing-job branch calls `placeJob(drag.job, target, fromSlot)` when `fromSlot != target` (keep the `sourceSlot.jobs.getOrNull(fromIndex) == drag.job` guard, now using `editableSchedule.slotByKey(fromSlot)`); pool branch calls `placeJob(drag.job, target, null)`. Ignore a drop on a target whose day has `acceptsPlacement == false`: compute `val targetDay = week.days.firstOrNull { it.amKey == target || it.pmKey == target }` and return early when `targetDay?.acceptsPlacement != true`.
  5. Top bar — title column:

```kotlin
                        Column {
                            Text("Delivery Schedule")
                            Text(
                                text = buildString {
                                    append("Week of ${week.weekStart.format(java.time.format.DateTimeFormatter.ofPattern("EEE M/d", java.util.Locale.US))}")
                                    if (isAdminMode) append(if (pendingEdits > 0) " · $pendingEdits edit(s) sent" else " · Admin editing")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
```

  actions — before the Reset block:

```kotlin
                        if (schedule.isDated) {
                            IconButton(onClick = { weekOffset -= 1 }, enabled = week.canGoBack) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous week")
                            }
                            TextButton(onClick = { weekOffset = 0 }, enabled = !week.isDefaultWeek) { Text("This week") }
                            IconButton(onClick = { weekOffset += 1 }, enabled = week.canGoForward) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next week")
                            }
                        }
```

  Reset block condition becomes `if (isAdminMode && onResetWeek != null && week.isDefaultWeek)`; the confirm button label `"Confirm: clear Mon–Fri dates"`, calls `::resetWeek`.
  6. Notices card — directly after the existing "Queued edits…" card:

```kotlin
                    if (notices.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(Modifier.padding(KKCSpacing.cardPaddingSmall), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    notices.forEach { notice ->
                                        Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                                IconButton(onClick = onDismissNotices) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss messages")
                                }
                            }
                        }
                    }
```

  Change the "Queued edits…" card text to: `"Edits go to Hours Tracker right away when it's reachable. Slots marked Pending are queued on this tablet and apply within about 15 seconds of reconnecting."` and show it when `isAdminMode && pendingSlotKeys.isNotEmpty()`.
  7. Grid: `DeliveryScheduleGrid(week = week, …)` replacing `schedule = editableSchedule`; add `pendingSlotKeys = pendingSlotKeys`. Callbacks: `onAddJob = { slotKey, job -> placeJob(job, slotKey, null); activeAddSlot = null }`, `onSaveJob = { slotKey, index, job -> replaceSlot(slotKey, editableSchedule.slotByKey(slotKey).jobs.mapIndexed { i, e -> if (i == index) job else e }) }`, `onRemoveJob = ::removeJob`.
  8. `DeliveryScheduleGrid(week: DeliveryWeekView, pendingSlotKeys: Set<String>, …)`: `val dayCount = week.days.size.coerceAtLeast(1)`, iterate `week.days.forEach { day -> DeliveryDayColumn(day = day, pendingSlotKeys = pendingSlotKeys, …) }`.
  9. `DeliveryDayColumn(day: DeliveryDayView, pendingSlotKeys: Set<String>, …)`: header `Text(text = day.label + if (day.state == DeliveryDayState.TODAY) " · Today" else "", color = if (day.state == DeliveryDayState.PAST) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, …)`; in the period loop `val slotKey = day.slotKey(period)`, `val slot = day.slot(period)`, `isValidDropTarget = dragState != null && dragState.sourceSlotKey != slotKey && slot.jobs.size < MAX_DELIVERY_JOBS_PER_SLOT && day.acceptsPlacement`; pass `acceptsPlacement = day.acceptsPlacement` and `isPending = slotKey in pendingSlotKeys` to `DeliveryPeriodCell`.
  10. `DeliveryPeriodCell(…, acceptsPlacement: Boolean = true, isPending: Boolean = false, …)`: the admin "Add" affordance shows only when `isAdminMode && acceptsPlacement`; when `isPending`, next to the AM/PM label render `Text("Pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)`.
  11. Imports: `com.kkc.sheettracker.data.DeliveryDayState`, `com.kkc.sheettracker.data.DeliveryDayView`, `com.kkc.sheettracker.data.DeliveryWeekView`, `com.kkc.sheettracker.data.MAX_DELIVERY_JOBS_PER_SLOT`, `com.kkc.sheettracker.data.buildDeliveryWeek`, `com.kkc.sheettracker.data.defaultDeliveryWeekStart`, `com.kkc.sheettracker.data.shopToday`, `androidx.compose.material.icons.filled.ChevronLeft`, `androidx.compose.material.icons.filled.ChevronRight`. Remove the now-unused `DELIVERY_DAYS` / `DELIVERY_DAY_LABELS` imports.

- [ ] **Step 6: UnifiedJobsScreen wiring.** After `val adminSyncClient = remember(adminSyncServerUrl) { … }` add:

```kotlin
    val deliveryEditor = remember(adminSyncClient, deliveryScheduleRequestStore, tabletId) {
        DeliveryScheduleEditor(adminSyncClient?.asDeliveryRemote(), deliveryScheduleRequestStore, tabletId)
    }
    var deliveryNotices by remember { mutableStateOf(emptyList<String>()) }
    var deliveryPendingRequest by remember { mutableStateOf<DeliveryScheduleEditRequest?>(null) }
    // HT polls request files every 15 s and deletes them once applied; re-read ours on the same
    // cadence while the dialog is open so "Pending" clears when the edit lands.
    LaunchedEffect(showScheduleDialog, deliveryScheduleBinding.dialogSchedule) {
        while (showScheduleDialog) {
            deliveryPendingRequest = withContext(Dispatchers.IO) { deliveryScheduleRequestStore.readPending(tabletId) }
            delay(15_000)
        }
    }
    val deliveryDialogSchedule = remember(deliveryScheduleBinding.dialogSchedule, deliveryPendingRequest) {
        overlayPendingDeliveryRequest(deliveryScheduleBinding.dialogSchedule, deliveryPendingRequest)
    }
    fun runDeliveryEdit(edit: suspend (DeliverySchedule) -> DeliveryEditOutcome) {
        saveScope.launch {
            val outcome = withContext(Dispatchers.IO) { edit(deliveryDialogSchedule) }
            if (outcome is DeliveryEditOutcome.Applied) deliveryScheduleBinding.applyCanonicalResponse(outcome.schedule)
            if (outcome.notices.isNotEmpty()) deliveryNotices = deliveryNotices + outcome.notices
            deliveryPendingRequest = withContext(Dispatchers.IO) { deliveryScheduleRequestStore.readPending(tabletId) }
        }
    }
```

(If `showScheduleDialog` is declared below this point, move this block to just before `if (showScheduleDialog) { DeliveryScheduleDialog(` instead; it must be in the composable body, not inside the `if`.) Replace the `DeliveryScheduleDialog(...)` call with:

```kotlin
        DeliveryScheduleDialog(
            schedule = deliveryDialogSchedule,
            onDismiss = { showScheduleDialog = false; deliveryNotices = emptyList() },
            isAdminMode = adminMode,
            availableJobs = deliveryPickerJobs,
            onPlaceJob = { job, toSlot, fromSlot -> runDeliveryEdit { deliveryEditor.placeJob(it, job, toSlot, fromSlot) } },
            onRemoveJob = { job, fromSlot -> runDeliveryEdit { deliveryEditor.removeJob(it, job, fromSlot) } },
            onReplaceSlot = { slot, jobs -> runDeliveryEdit { deliveryEditor.replaceSlot(it, slot, jobs) } },
            onResetWeek = { runDeliveryEdit { deliveryEditor.resetWeek(it) } },
            notices = deliveryNotices,
            onDismissNotices = { deliveryNotices = emptyList() },
            pendingSlotKeys = pendingDeliverySlotKeys(deliveryPendingRequest)
        )
```

Imports: `com.kkc.sheettracker.data.DeliveryEditOutcome`, `com.kkc.sheettracker.data.DeliveryScheduleEditor`, `com.kkc.sheettracker.data.asDeliveryRemote`, `com.kkc.sheettracker.data.overlayPendingDeliveryRequest`, `com.kkc.sheettracker.data.pendingDeliverySlotKeys`, `kotlinx.coroutines.delay` (if missing). Remove the now-unused `DeliveryScheduleSlotEdit` import. The four `DeliveryScheduleBanner(` call sites need no change.

- [ ] **Step 7: Run tests + compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.*" --tests "com.kkc.sheettracker.ui.jobs.*" --tests "com.kkc.sheettracker.data.Delivery*"`
Expected: PASS. Then `.\gradlew.bat :app:compileDebugKotlin` — BUILD SUCCESSFUL. Grep check: `rg "deliveryDayState\(|totalDeliveryCount\(" app/src` returns nothing.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBannerTest.kt app/src/test/java/com/kkc/sheettracker/ui/jobs/DeliveryScheduleWiringTest.kt
git commit -m "feat(delivery): real dates, week navigation and pending/failed edits in banner and dialog"
```

---

# Part B — Handoff flags

## File map (Part B)

| File | Responsibility |
|---|---|
| `data/models/HandoffFlagModels.kt` (create) | `HandoffTruck`, `HandoffCount`, `HandoffItemFlags`, `HandoffFlagRef`, `HandoffTruckListEntry` |
| `data/HandoffFlags.kt` (create) | `handoffFlagRef(...)` mapping, `parseHandoffItemFlags`, `loadHandoffItemFlags` |
| `data/HandoffFlagRequestStore.kt` (create) | Per-tablet append-only sidecar + pending read |
| `data/HandoffFlagsClient.kt` (create) | `PUT …/handoff/flags`, `GET …/handoff/truck-list` |
| `data/HandoffFlagsService.kt` (create) | Tap orchestration, change signal, pure display rules |
| `data/TrackerChangeMonitor.kt` (modify) | `onSpecialtyJobsChanged` callback |
| `navigation/NavGraph.kt` (modify) | Wire callback → signal; pass `tabletId` to Hardwoods |
| `ui/handoff/HandoffTruckChip.kt` (create) | Chip + menu, `rememberHandoffFlags` |
| `ui/specialty/SpecialtyJobDetailScreen.kt`, `ui/specialty/SpecialtyKanbanBoard.kt`, `ui/hardwoods/HardwoodsWorkspaceScreen.kt`, `navigation/ArchiveJobDetailHost.kt` (modify) | Show chip |

All paths below are under `app/src/main/java/com/kkc/sheettracker/` (tests under `app/src/test/java/com/kkc/sheettracker/`).

---

### Task 8: Flag models, item → (source, id) mapping, tolerant parser

**Files:**
- Create: `data/models/HandoffFlagModels.kt`
- Create: `data/HandoffFlags.kt`
- Test: `data/HandoffFlagsTest.kt`

**Interfaces:**
- Produces:
  - `enum class HandoffTruck(val wire: String, val label: String) { LOOSE("loose", "Ship loose"), SHOP("shop", "Shop install"), LATER("later", "Coming later"); companion object { fun fromWire(value: String?): HandoffTruck? } }`
  - `enum class HandoffCount(val wire: String, val label: String) { QTY("qty", "Count by qty"), LINE("line", "Count by line"); companion object { fun fromWire(value: String?): HandoffCount? } }`
  - `data class HandoffItemFlags(val truck: HandoffTruck? = null, val count: HandoffCount? = null)`
  - `data class HandoffFlagRef(val source: String, val id: String) { val key: String; companion object { BOARD_STOCK, SPECIALTY_ITEMS, CHECKLIST, SOURCES; fun fromKey(key: String): HandoffFlagRef? } }`
  - `data class HandoffTruckListEntry(val ref: HandoffFlagRef, val autoTruck: HandoffTruck?, val effectiveTruck: HandoffTruck?, val effectiveCount: HandoffCount?, val excluded: Boolean)`
  - `fun handoffFlagRef(item: SpecialtyItem): HandoffFlagRef`; `fun handoffFlagRef(item: AdminBoardStockItem): HandoffFlagRef` (two overloads of the one mapping, used everywhere)
  - `internal fun parseHandoffItemFlags(json: String): Map<HandoffFlagRef, HandoffItemFlags>`
  - `fun loadHandoffItemFlags(baseDir: File, jobFolderName: String): Map<HandoffFlagRef, HandoffItemFlags>`

- [ ] **Step 1: Write failing tests** — `HandoffFlagsTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.SpecialtyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HandoffFlagsTest {
    private fun specialty(id: String) = SpecialtyItem(id = id, name = "Item")
    private fun board(id: String) = AdminBoardStockItem(
        id = id, material = "Maple", name = "Crown", feet = 10.0, mode = "bd_ft",
        ripLength = 10, createdAt = "", createdBy = ""
    )

    @Test
    fun mapsAllFiveItemKinds() {
        // 1. board stock row: trimmed id
        assertEquals(HandoffFlagRef("board_stock", "5f0c"), handoffFlagRef(board(" 5f0c ")))
        // 2. office specialty item, raw uuid and default:<i>:<name>
        assertEquals(HandoffFlagRef("specialty_items", "9a1e-uuid"), handoffFlagRef(specialty("9a1e-uuid")))
        assertEquals(HandoffFlagRef("specialty_items", "default:0:bumps"), handoffFlagRef(specialty("default:0:bumps")))
        // 3. tablet-created item keeps the tablet: prefix
        assertEquals(HandoffFlagRef("specialty_items", "tablet:abc"), handoffFlagRef(specialty("tablet:abc")))
        // 4. office item a tablet edited: merge keeps the office id, so it maps to the office id
        assertEquals(HandoffFlagRef("specialty_items", "office-1"), handoffFlagRef(specialty("office-1")))
        // 5. checklist row shown in Specialty: strip KST's checklist: prefix
        assertEquals(HandoffFlagRef("checklist", "c-123"), handoffFlagRef(specialty("checklist:c-123")))
    }

    @Test
    fun keySplitsOnFirstColonOnly() {
        assertEquals(HandoffFlagRef("specialty_items", "tablet:9a1e"), HandoffFlagRef.fromKey("specialty_items:tablet:9a1e"))
        assertEquals("specialty_items:default:0:bumps", HandoffFlagRef("specialty_items", "default:0:bumps").key)
        assertNull(HandoffFlagRef.fromKey("nocolon"))
        assertNull(HandoffFlagRef.fromKey(":id"))
        assertNull(HandoffFlagRef.fromKey("source:"))
    }

    @Test
    fun parsesFlagsAndTreatsUnknownAsAuto() {
        val flags = parseHandoffItemFlags(
            """
            {"schemaVersion":1,"itemFlags":{
              "board_stock:5f0c":{"truck":"loose"},
              "specialty_items:tablet:9a1e":{"truck":"later","count":"line"},
              "specialty_items:default:0:bumps":{"count":"qty"},
              "checklist:c-123":{"truck":"LOOSE","count":"qty"},
              "checklist:c-9":{"truck":"teleport","future":"x"},
              "nocolon":{"truck":"shop"},
              "board_stock:bad":"not-an-object"
            },"itemFlagsSetAt":{"board_stock:5f0c":{"truck":"2026-10-08T14:00:00.123Z"}},"status":"ok"}
            """.trimIndent()
        )
        assertEquals(HandoffItemFlags(truck = HandoffTruck.LOOSE), flags[HandoffFlagRef("board_stock", "5f0c")])
        assertEquals(HandoffItemFlags(HandoffTruck.LATER, HandoffCount.LINE), flags[HandoffFlagRef("specialty_items", "tablet:9a1e")])
        assertEquals(HandoffItemFlags(count = HandoffCount.QTY), flags[HandoffFlagRef("specialty_items", "default:0:bumps")])
        // "LOOSE" is not a valid value: truck is Auto, count survives.
        assertEquals(HandoffItemFlags(count = HandoffCount.QTY), flags[HandoffFlagRef("checklist", "c-123")])
        // Entirely unknown => Auto => no entry.
        assertNull(flags[HandoffFlagRef("checklist", "c-9")])
        assertEquals(4, flags.size)
    }

    @Test
    fun missingCorruptOrConflictCopyMeansNoFlags() {
        val base = Files.createTempDirectory("handoff-flags").toFile()
        val job = "670 - ESCHRICH"
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        val admin = File(base, "$job/.metadata/admin").apply { mkdirs() }
        File(admin, "handoff.sync-conflict-20261008-140000-ABCDEFG.json").writeText("""{"itemFlags":{"board_stock:x":{"truck":"loose"}}}""")
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        File(admin, "handoff.json").writeText("{corrupt")
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        File(admin, "handoff.json").writeText("""{"schemaVersion":1}""")
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        File(admin, "handoff.json").writeText("""{"itemFlags":{"board_stock:x":{"truck":"shop"}}}""")
        assertEquals(HandoffTruck.SHOP, loadHandoffItemFlags(base, job)[HandoffFlagRef("board_stock", "x")]?.truck)
    }
}
```

Before writing the test, confirm `SpecialtyItem` and `AdminBoardStockItem` constructor parameter names/defaults in `data/models/Models.kt:803` and `:864`; adjust the two factory helpers if a required parameter is missing.

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HandoffFlagsTest"`
Expected: FAIL (unresolved).

- [ ] **Step 3: Implement models** — `data/models/HandoffFlagModels.kt`:

```kotlin
package com.kkc.sheettracker.data.models

/**
 * Handoff item flags (HT METADATA_CONTRACT.md §2.12). Every flag is optional; null = Auto (HT's
 * packet rules decide). Wire values are lower-case and case-sensitive — never write `name`.
 */
enum class HandoffTruck(val wire: String, val label: String) {
    LOOSE("loose", "Ship loose"),
    SHOP("shop", "Shop install"),
    LATER("later", "Coming later");

    companion object {
        fun fromWire(value: String?): HandoffTruck? = entries.firstOrNull { it.wire == value }
    }
}

enum class HandoffCount(val wire: String, val label: String) {
    QTY("qty", "Count by qty"),
    LINE("line", "Count by line");

    companion object {
        fun fromWire(value: String?): HandoffCount? = entries.firstOrNull { it.wire == value }
    }
}

data class HandoffItemFlags(
    val truck: HandoffTruck? = null,
    val count: HandoffCount? = null
) {
    val isAuto: Boolean get() = truck == null && count == null
}

/** `handoff.json` key parts. The key is `"<source>:<id>"`; ids may themselves contain colons. */
data class HandoffFlagRef(val source: String, val id: String) {
    val key: String get() = "$source:$id"

    companion object {
        const val BOARD_STOCK = "board_stock"
        const val SPECIALTY_ITEMS = "specialty_items"
        const val CHECKLIST = "checklist"
        val SOURCES = setOf(BOARD_STOCK, SPECIALTY_ITEMS, CHECKLIST)

        /** Splits on the FIRST colon only. */
        fun fromKey(key: String): HandoffFlagRef? {
            val split = key.indexOf(':')
            if (split <= 0 || split == key.length - 1) return null
            return HandoffFlagRef(key.substring(0, split), key.substring(split + 1))
        }
    }
}

/** One row of HT's `GET /api/jobs/{folder}/handoff/truck-list`, reduced to what the tablet shows. */
data class HandoffTruckListEntry(
    val ref: HandoffFlagRef,
    val autoTruck: HandoffTruck?,
    val effectiveTruck: HandoffTruck?,
    val effectiveCount: HandoffCount?,
    val excluded: Boolean
)
```

- [ ] **Step 4: Implement mapping + parser** — `data/HandoffFlags.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.SpecialtyItem
import java.io.File

/**
 * THE mapping from a KST item to its `handoff.json` (source, id). Used for reading keys, the REST
 * body and the sidecar — never build a ref any other way.
 *
 * Merged specialty items already carry the right id: office items keep their raw id (also when a
 * tablet overlay edited them, see SpecialtyProgressStore.mergeSpecialtyItems), tablet-created
 * items carry `tablet:<uuid>`, checklist rows carry KST's `checklist:<id>` which HT keys by the
 * raw id (HT silently accepts a wrong id, so this strip is load-bearing).
 */
fun handoffFlagRef(item: SpecialtyItem): HandoffFlagRef =
    if (item.id.startsWith("checklist:")) HandoffFlagRef(HandoffFlagRef.CHECKLIST, item.id.removePrefix("checklist:"))
    else HandoffFlagRef(HandoffFlagRef.SPECIALTY_ITEMS, item.id)

fun handoffFlagRef(item: AdminBoardStockItem): HandoffFlagRef =
    HandoffFlagRef(HandoffFlagRef.BOARD_STOCK, item.id.trim())

internal fun handoffJsonFile(baseDir: File, jobFolderName: String): File =
    File(baseDir, "$jobFolderName/.metadata/admin/handoff.json")

/**
 * Reads `itemFlags` from HT's `handoff.json` (HT is the only writer). Unknown flag names or values
 * are Auto; an entry left with no known flag is dropped; other top-level keys are ignored.
 */
internal fun parseHandoffItemFlags(json: String): Map<HandoffFlagRef, HandoffItemFlags> {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
    val itemFlags = root.get("itemFlags")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
    return itemFlags.entrySet().mapNotNull { (key, value) ->
        val ref = HandoffFlagRef.fromKey(key) ?: return@mapNotNull null
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val flags = HandoffItemFlags(
            truck = HandoffTruck.fromWire(obj.primitiveString("truck")),
            count = HandoffCount.fromWire(obj.primitiveString("count"))
        )
        if (flags.isAuto) null else ref to flags
    }.toMap()
}

private fun JsonObject.primitiveString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

/** Missing file, corrupt JSON or missing `itemFlags` all mean "no flags". Call on IO. */
fun loadHandoffItemFlags(baseDir: File, jobFolderName: String): Map<HandoffFlagRef, HandoffItemFlags> {
    val file = handoffJsonFile(baseDir, jobFolderName)
    if (!file.isFile) return emptyMap()
    return runCatching { parseHandoffItemFlags(file.readText()) }.getOrElse { emptyMap() }
}
```

- [ ] **Step 5: Run to verify pass** — same command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/HandoffFlagModels.kt app/src/main/java/com/kkc/sheettracker/data/HandoffFlags.kt app/src/test/java/com/kkc/sheettracker/data/HandoffFlagsTest.kt
git commit -m "feat(handoff): read handoff.json item flags with one item-to-key mapping"
```

---

### Task 9: Per-tablet flag request sidecar

**Files:**
- Create: `data/HandoffFlagRequestStore.kt`
- Test: `data/HandoffFlagRequestStoreTest.kt`

**Interfaces:**
- Consumes: Task 8 models; `atomicWriteFile`.
- Produces:
  - `data class PendingTruckFlag(val truck: HandoffTruck?, val requestedAt: Instant)` (`truck == null` = pending "back to Auto")
  - `class HandoffFlagRequestStore(baseDir: File, tabletId: String)`:
    - `suspend fun appendTruckRequest(jobFolderName: String, ref: HandoffFlagRef, truck: HandoffTruck?, requestedAt: String): Boolean` (false = invalid, nothing written)
    - `fun pendingTruckFlags(jobFolderName: String, now: Instant, ttl: Duration = PENDING_TTL): Map<HandoffFlagRef, PendingTruckFlag>`
    - `companion object { val PENDING_TTL: Duration = Duration.ofSeconds(120) }`

- [ ] **Step 1: Write failing tests** — `HandoffFlagRequestStoreTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffTruck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class HandoffFlagRequestStoreTest {
    private val job = "670 - ESCHRICH"
    private val ref = HandoffFlagRef("checklist", "c-123")

    private fun setup(): Pair<File, HandoffFlagRequestStore> {
        val base = Files.createTempDirectory("handoff-req").toFile()
        return base to HandoffFlagRequestStore(base, "T1")
    }

    private fun sidecar(base: File) = File(base, "$job/.metadata/admin/handoff_flags_request.T1.json")

    @Test
    fun writesExactContractShapeWithOnlyTheTouchedFlag() = runBlocking {
        val (base, store) = setup()
        assertTrue(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00.123Z"))
        val bytes = sidecar(base).readBytes()
        assertFalse("no BOM", bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte())
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("T1", root.getString("tabletId"))
        val req = root.getJSONArray("requests").getJSONObject(0)
        assertEquals(setOf("requestId", "requestedAt", "source", "id", "truck"), req.keySet())
        assertEquals("2026-10-08T14:00:00.123Z", req.getString("requestedAt"))
        assertEquals("checklist", req.getString("source"))
        assertEquals("c-123", req.getString("id"))
        assertEquals("loose", req.getString("truck"))
        Instant.parse(req.getString("requestedAt"))
    }

    @Test
    fun autoIsAnExplicitNull() = runBlocking {
        val (base, store) = setup()
        store.appendTruckRequest(job, ref, null, "2026-10-08T14:00:00Z")
        val req = JSONObject(sidecar(base).readText()).getJSONArray("requests").getJSONObject(0)
        assertTrue(req.has("truck"))
        assertTrue(req.isNull("truck"))
        assertTrue(sidecar(base).readText().contains("\"truck\":null"))
    }

    @Test
    fun appendsToExistingFileAndKeepsForeignEntries() = runBlocking {
        val (base, store) = setup()
        sidecar(base).apply { parentFile.mkdirs() }.writeText(
            """{"schemaVersion":1,"tabletId":"T1","requests":[{"requestId":"old","requestedAt":"2026-10-08T13:00:00Z","source":"board_stock","id":"x","count":"qty"}]}"""
        )
        store.appendTruckRequest(job, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z")
        val requests = JSONObject(sidecar(base).readText()).getJSONArray("requests")
        assertEquals(2, requests.length())
        assertEquals("qty", requests.getJSONObject(0).getString("count"))
        assertEquals("shop", requests.getJSONObject(1).getString("truck"))
    }

    @Test
    fun twoConcurrentTapsBothLand() = runBlocking {
        val (base, store) = setup()
        coroutineScope {
            (0 until 20).forEach { i ->
                launch(Dispatchers.IO) {
                    store.appendTruckRequest(job, HandoffFlagRef("board_stock", "b$i"), HandoffTruck.LATER, Instant.now().toString())
                }
            }
        }
        assertEquals(20, JSONObject(sidecar(base).readText()).getJSONArray("requests").length())
    }

    @Test
    fun invalidRequestsAreNotWritten() = runBlocking {
        val (base, store) = setup()
        assertFalse(store.appendTruckRequest(job, HandoffFlagRef("bogus", "x"), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertFalse(store.appendTruckRequest(job, HandoffFlagRef("checklist", " "), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertFalse(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "1728396000000"))
        assertFalse(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00"))
        assertFalse(sidecar(base).exists())
    }

    @Test
    fun unreadableFileIsPreservedThenANewFileStarted() = runBlocking {
        val (base, store) = setup()
        sidecar(base).apply { parentFile.mkdirs() }.writeText("{broken")
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z")
        val admin = sidecar(base).parentFile
        val backup = admin.listFiles()!!.single { it.name.startsWith("handoff_flags_request.T1.json.unreadable-") }
        assertEquals("{broken", backup.readText())
        assertEquals(1, JSONObject(sidecar(base).readText()).getJSONArray("requests").length())
    }

    @Test
    fun pendingIsLastRequestPerRefWithinTtl() = runBlocking {
        val (_, store) = setup()
        val t0 = Instant.parse("2026-10-08T14:00:00Z")
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        store.appendTruckRequest(job, ref, null, t0.plusSeconds(5).toString())
        store.appendTruckRequest(job, HandoffFlagRef("board_stock", "b"), HandoffTruck.SHOP, t0.minusSeconds(300).toString())
        val pending = store.pendingTruckFlags(job, now = t0.plusSeconds(10))
        assertEquals(setOf(ref), pending.keys)
        assertEquals(null, pending.getValue(ref).truck)
        assertTrue(store.pendingTruckFlags(job, now = t0.plusSeconds(200)).isEmpty())
    }

    @Test
    fun rejectedFileIsLeftAlone() = runBlocking {
        val (base, store) = setup()
        val rejected = File(sidecar(base).parentFile.apply { mkdirs() }, "handoff_flags_request.T1.json.rejected").apply { writeText("x") }
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z")
        assertEquals("x", rejected.readText())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HandoffFlagRequestStoreTest"`
Expected: FAIL (unresolved).

- [ ] **Step 3: Implement** — `data/HandoffFlagRequestStore.kt`:

```kotlin
package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffTruck
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingTruckFlag(val truck: HandoffTruck?, val requestedAt: Instant)

/**
 * Appends tablet flag changes to `<job>/.metadata/admin/handoff_flags_request.<tabletId>.json`
 * (HT METADATA_CONTRACT.md §2.12). HT applies the file every 30 s / before packet generation /
 * when the office opens the job, and deletes it only if its bytes didn't change while it read.
 *
 * - Only the touched flag is written; Auto is an explicit JSON null (org.json NULL, not Gson).
 * - One invalid request makes HT quarantine the WHOLE file as `.rejected`, so validate first.
 * - Earlier entries (even ones this build doesn't understand) are preserved verbatim.
 * - org.json writes UTF-8 without a BOM via `writeText`.
 */
class HandoffFlagRequestStore(private val baseDir: File, private val tabletId: String) {

    suspend fun appendTruckRequest(
        jobFolderName: String,
        ref: HandoffFlagRef,
        truck: HandoffTruck?,
        requestedAt: String
    ): Boolean {
        if (ref.source !in HandoffFlagRef.SOURCES || ref.id.isBlank()) return false
        if (runCatching { Instant.parse(requestedAt) }.isFailure) return false
        val file = requestFile(jobFolderName)
        lockFor(file).withLock {
            val root = readOrStartNew(file)
            val request = JSONObject()
                .put("requestId", UUID.randomUUID().toString())
                .put("requestedAt", requestedAt)
                .put("source", ref.source)
                .put("id", ref.id)
                .put("truck", truck?.wire ?: JSONObject.NULL)
            root.getJSONArray("requests").put(request)
            atomicWriteFile(file, root.toString())
        }
        return true
    }

    /** Last truck request per item still in this tablet's file and younger than [ttl]. */
    fun pendingTruckFlags(jobFolderName: String, now: Instant, ttl: Duration = PENDING_TTL): Map<HandoffFlagRef, PendingTruckFlag> {
        val file = requestFile(jobFolderName)
        if (!file.isFile) return emptyMap()
        val requests = runCatching { JSONObject(file.readText()).getJSONArray("requests") }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<HandoffFlagRef, PendingTruckFlag>()
        for (i in 0 until requests.length()) {
            val obj = requests.optJSONObject(i) ?: continue
            if (!obj.has("truck")) continue
            val at = runCatching { Instant.parse(obj.getString("requestedAt")) }.getOrNull() ?: continue
            if (Duration.between(at, now) > ttl) continue
            val ref = HandoffFlagRef(obj.optString("source"), obj.optString("id"))
            val truck = if (obj.isNull("truck")) null else HandoffTruck.fromWire(obj.optString("truck")) ?: continue
            val previous = result[ref]
            if (previous == null || !at.isBefore(previous.requestedAt)) result[ref] = PendingTruckFlag(truck, at)
        }
        return result
    }

    private fun readOrStartNew(file: File): JSONObject {
        if (file.exists()) {
            val parsed = runCatching { JSONObject(file.readText()) }.getOrNull()
                ?.takeIf { it.optJSONArray("requests") != null }
            if (parsed != null) return parsed
            val backup = File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}")
            runCatching { file.copyTo(backup, overwrite = false) }
                .onFailure { Log.w(TAG, "Could not back up unreadable ${file.name}", it) }
            Log.w(TAG, "Unreadable ${file.name}; kept a copy as ${backup.name} and started a new file")
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("tabletId", tabletId)
            .put("requests", JSONArray())
    }

    private fun requestFile(jobFolderName: String) =
        File(baseDir, "$jobFolderName/.metadata/admin/handoff_flags_request.$tabletId.json")

    private fun lockFor(file: File): Mutex = locks.getOrPut(file.absolutePath) { Mutex() }

    companion object {
        private const val TAG = "HandoffFlagRequests"
        val PENDING_TTL: Duration = Duration.ofSeconds(120)
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
```

Note on the `Instant.parse` check: it rejects epoch millis and zone-less local times (the two forms the contract forbids) and accepts both `Instant.toString()` shapes.

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/HandoffFlagRequestStore.kt app/src/test/java/com/kkc/sheettracker/data/HandoffFlagRequestStoreTest.kt
git commit -m "feat(handoff): append-only per-tablet flag request sidecar with pending read"
```

---

### Task 10: HT REST client for flags + truck list

**Files:**
- Create: `data/HandoffFlagsClient.kt`
- Test: `data/HandoffFlagsClientTest.kt`

**Interfaces:**
- Consumes: Task 8 models.
- Produces:
  - `sealed interface HandoffFlagPutResult { data class Applied(val flags: HandoffItemFlags); object Skipped; data class Failed(val status: Int?) }`
  - `class HandoffFlagsClient(serverUrl: String)`:
    - `suspend fun putTruck(folderName: String, ref: HandoffFlagRef, truck: HandoffTruck?, requestedAt: String): HandoffFlagPutResult`
    - `suspend fun fetchTruckList(folderName: String): Map<HandoffFlagRef, HandoffTruckListEntry>?`

- [ ] **Step 1: Write failing tests** — `HandoffFlagsClientTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HandoffFlagsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HandoffFlagsClient
    private val folder = "670 - ESCHRICH 1364 VICTORIAN"

    @Before fun setUp() { server = MockWebServer().apply { start() }; client = HandoffFlagsClient(server.url("/").toString()) }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun putSendsOnlyTruckAndEncodesFolder() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"source":"checklist","id":"c-1","flags":{"truck":"loose","count":"qty"},"skipped":false}"""))
        val result = client.putTruck(folder, HandoffFlagRef("checklist", "c-1"), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z")
        assertEquals(HandoffFlagPutResult.Applied(HandoffItemFlags(HandoffTruck.LOOSE, HandoffCount.QTY)), result)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/jobs/670%20-%20ESCHRICH%201364%20VICTORIAN/handoff/flags", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals(setOf("source", "id", "truck", "requestedAt"), body.keySet())
        assertEquals("loose", body.getString("truck"))
    }

    @Test
    fun putAutoSendsExplicitNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"source":"checklist","id":"c-1","flags":null,"skipped":false}"""))
        val result = client.putTruck(folder, HandoffFlagRef("checklist", "c-1"), null, "2026-10-08T14:00:00Z")
        assertEquals(HandoffFlagPutResult.Applied(HandoffItemFlags()), result)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"truck\":null"))
    }

    @Test
    fun skippedAndFailures() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"source":"checklist","id":"c-1","flags":null,"skipped":true}"""))
        server.enqueue(MockResponse().setResponseCode(422))
        server.enqueue(MockResponse().setResponseCode(503))
        val ref = HandoffFlagRef("checklist", "c-1")
        assertEquals(HandoffFlagPutResult.Skipped, client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
        assertEquals(HandoffFlagPutResult.Failed(422), client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
        assertEquals(HandoffFlagPutResult.Failed(503), client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
        server.shutdown()
        assertEquals(HandoffFlagPutResult.Failed(null), client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
    }

    @Test
    fun truckListParsesAutoEffectiveAndExcluded() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"mode":"frameless","items":[
                {"source":"board_stock","sourceLabel":"Board stock","id":"5f0c","label":"Crown","detail":"","auto":{"truck":"loose","count":null},"flags":{},"effective":{"truck":"loose","count":null},"excluded":false},
                {"source":"checklist","id":"c-1","label":"Bumps","auto":{"truck":"shop","count":"qty"},"flags":{"truck":"later"},"effective":{"truck":"later","count":"qty"},"excluded":false},
                {"source":"specialty_items","id":"tablet:9a1e","label":"X","auto":{"truck":"loose"},"flags":{},"effective":{"truck":null},"excluded":true}
            ]}"""
        ))
        val list = client.fetchTruckList(folder)!!
        assertEquals("/api/jobs/670%20-%20ESCHRICH%201364%20VICTORIAN/handoff/truck-list", server.takeRequest().path)
        assertEquals(HandoffTruck.LOOSE, list.getValue(HandoffFlagRef("board_stock", "5f0c")).effectiveTruck)
        assertEquals(HandoffCount.QTY, list.getValue(HandoffFlagRef("checklist", "c-1")).effectiveCount)
        assertTrue(list.getValue(HandoffFlagRef("specialty_items", "tablet:9a1e")).excluded)
    }

    @Test
    fun truckListNullOnFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.fetchTruckList(folder))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HandoffFlagsClientTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** — `data/HandoffFlagsClient.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface HandoffFlagPutResult {
    data class Applied(val flags: HandoffItemFlags) : HandoffFlagPutResult
    /** The office changed this flag more recently; refresh, show theirs, don't retry. */
    object Skipped : HandoffFlagPutResult
    /** Any non-2xx or no connection (status null). The sidecar carries the change. */
    data class Failed(val status: Int?) : HandoffFlagPutResult
}

/** Hours Tracker handoff routes (same app/port as `/api/admin-sync/*`). Never throws. */
class HandoffFlagsClient(serverUrl: String) {
    private val base: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun jobUrl(folderName: String, vararg tail: String): HttpUrl =
        base.newBuilder().addPathSegments("api/jobs").addPathSegment(folderName).apply {
            tail.forEach { addPathSegment(it) }
        }.build()

    suspend fun putTruck(folderName: String, ref: HandoffFlagRef, truck: HandoffTruck?, requestedAt: String): HandoffFlagPutResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("source", ref.source)
                .put("id", ref.id)
                .put("truck", truck?.wire ?: JSONObject.NULL)
                .put("requestedAt", requestedAt)
                .toString()
            val request = Request.Builder().url(jobUrl(folderName, "handoff", "flags")).put(body.toRequestBody(json)).build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use HandoffFlagPutResult.Failed(response.code)
                    val obj = JSONObject(response.body?.string().orEmpty())
                    if (obj.optBoolean("skipped")) HandoffFlagPutResult.Skipped
                    else HandoffFlagPutResult.Applied(parseFlags(obj.optJSONObject("flags")))
                }
            }.getOrElse { HandoffFlagPutResult.Failed(null) }
        }

    /** HT computes Auto placement; never port those rules. Null on any failure. Side effect: HT applies pending requests first. */
    suspend fun fetchTruckList(folderName: String): Map<HandoffFlagRef, HandoffTruckListEntry>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(jobUrl(folderName, "handoff", "truck-list")).get().build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val items = JSONObject(response.body?.string().orEmpty()).optJSONArray("items") ?: return@use null
                    (0 until items.length()).mapNotNull { i ->
                        val obj = items.optJSONObject(i) ?: return@mapNotNull null
                        val ref = HandoffFlagRef(obj.optString("source"), obj.optString("id"))
                        if (ref.source.isBlank() || ref.id.isBlank()) return@mapNotNull null
                        val auto = parseFlags(obj.optJSONObject("auto"))
                        val effective = parseFlags(obj.optJSONObject("effective"))
                        ref to HandoffTruckListEntry(ref, auto.truck, effective.truck, effective.count, obj.optBoolean("excluded"))
                    }.toMap()
                }
            }.getOrNull()
        }

    private fun parseFlags(obj: JSONObject?): HandoffItemFlags {
        if (obj == null) return HandoffItemFlags()
        fun str(name: String) = if (obj.isNull(name)) null else obj.optString(name)
        return HandoffItemFlags(HandoffTruck.fromWire(str("truck")), HandoffCount.fromWire(str("count")))
    }

    private companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/HandoffFlagsClient.kt app/src/test/java/com/kkc/sheettracker/data/HandoffFlagsClientTest.kt
git commit -m "feat(handoff): REST client for flag PUT and truck list"
```

---

### Task 11: Service — tap orchestration, display rules, change signal

**Files:**
- Create: `data/HandoffFlagsService.kt`
- Modify: `data/TrackerChangeMonitor.kt` (constructor + `applyBatchedInvalidations`)
- Modify: `navigation/NavGraph.kt:262-276` (wire callback)
- Test: `data/HandoffFlagsServiceTest.kt`; add one test to `data/TrackerChangeMonitorSpecialtyTest.kt`

**Interfaces:**
- Consumes: Tasks 8-10.
- Produces:
  - `object HandoffFlagsChangeSignal { val version: StateFlow<Long>; fun bump() }`
  - `data class HandoffFlagsSnapshot(val stored: Map<HandoffFlagRef, HandoffItemFlags> = emptyMap(), val pending: Map<HandoffFlagRef, PendingTruckFlag> = emptyMap(), val truckList: Map<HandoffFlagRef, HandoffTruckListEntry>? = null)`
  - `data class HandoffTruckDisplay(val label: String, val selected: HandoffTruck?, val isAuto: Boolean, val isPending: Boolean, val shipsLoose: Boolean?, val countLabel: String?)`
  - `fun handoffTruckDisplay(ref: HandoffFlagRef, snapshot: HandoffFlagsSnapshot): HandoffTruckDisplay`
  - `class HandoffFlagsService(baseDir: File, tabletId: String, clientProvider: suspend () -> HandoffFlagsClient?, restScope: CoroutineScope, clock: Clock = Clock.systemUTC())`:
    - `fun loadLocal(jobFolderName: String): HandoffFlagsSnapshot` (IO; stored + pending)
    - `suspend fun fetchTruckList(jobFolderName: String): Map<HandoffFlagRef, HandoffTruckListEntry>?`
    - `suspend fun setTruck(jobFolderName: String, ref: HandoffFlagRef, truck: HandoffTruck?): Boolean` — returns after the sidecar write; REST runs in `restScope`.
  - `TrackerChangeMonitor(..., onSpecialtyJobsChanged: ((Set<String>?) -> Unit)? = null)` — `null` set = all jobs.

- [ ] **Step 1: Write failing tests** — `HandoffFlagsServiceTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

class HandoffFlagsServiceTest {
    private val ref = HandoffFlagRef("board_stock", "5f0c")
    private val now = Instant.parse("2026-10-08T14:00:00Z")

    @Test
    fun offlineAutoNeverClaimsShipLoose() {
        val d = handoffTruckDisplay(ref, HandoffFlagsSnapshot())
        assertEquals("Auto", d.label)
        assertTrue(d.isAuto)
        assertNull(d.shipsLoose)
        assertNull(d.selected)
    }

    @Test
    fun onlineAutoShowsEffectivePlacement() {
        val list = mapOf(ref to HandoffTruckListEntry(ref, HandoffTruck.LOOSE, HandoffTruck.LOOSE, null, excluded = false))
        val d = handoffTruckDisplay(ref, HandoffFlagsSnapshot(truckList = list))
        assertEquals("Auto · Ship loose", d.label)
        assertEquals(true, d.shipsLoose)
    }

    @Test
    fun excludedRowSaysSo() {
        val list = mapOf(ref to HandoffTruckListEntry(ref, HandoffTruck.LOOSE, null, null, excluded = true))
        assertEquals("Auto · Excluded by office", handoffTruckDisplay(ref, HandoffFlagsSnapshot(truckList = list)).label)
    }

    @Test
    fun storedFlagAndReadOnlyCount() {
        val d = handoffTruckDisplay(ref, HandoffFlagsSnapshot(stored = mapOf(ref to HandoffItemFlags(HandoffTruck.SHOP, HandoffCount.LINE))))
        assertEquals("Shop install", d.label)
        assertEquals(HandoffTruck.SHOP, d.selected)
        assertEquals(false, d.shipsLoose)
        assertEquals("Count by line", d.countLabel)
    }

    @Test
    fun pendingOverridesStoredIncludingBackToAuto() {
        val snapshot = HandoffFlagsSnapshot(
            stored = mapOf(ref to HandoffItemFlags(HandoffTruck.LOOSE)),
            pending = mapOf(ref to PendingTruckFlag(null, now))
        )
        val d = handoffTruckDisplay(ref, snapshot)
        assertTrue(d.isPending)
        assertTrue(d.isAuto)
        assertEquals("Auto", d.label)
    }

    @Test
    fun setTruckWritesSidecarThenPutsAndSkipBumpsSignal() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"source":"board_stock","id":"5f0c","flags":null,"skipped":true}"""))
        val base = Files.createTempDirectory("handoff-svc").toFile()
        val job = "670 - E"
        val service = HandoffFlagsService(
            baseDir = base,
            tabletId = "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertTrue(service.setTruck(job, ref, HandoffTruck.LOOSE))
        val sidecar = JSONObject(File(base, "$job/.metadata/admin/handoff_flags_request.T1.json").readText())
        val written = sidecar.getJSONArray("requests").getJSONObject(0)
        val put = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        // Same requestedAt in both writes.
        assertEquals(written.getString("requestedAt"), put.getString("requestedAt"))
        assertEquals(now.toString(), put.getString("requestedAt"))
        val deadline = System.currentTimeMillis() + 5_000
        while (HandoffFlagsChangeSignal.version.value < before + 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(HandoffFlagsChangeSignal.version.value >= before + 2) // sidecar write + skipped refresh
        server.shutdown()
    }

    @Test
    fun setTruckWithNoServerStillWritesSidecar() = runBlocking {
        val base = Files.createTempDirectory("handoff-svc-offline").toFile()
        val service = HandoffFlagsService(base, "T1", clientProvider = { null }, restScope = CoroutineScope(Dispatchers.IO))
        assertTrue(service.setTruck("670 - E", ref, HandoffTruck.LATER))
        val snapshot = service.loadLocal("670 - E")
        assertEquals(HandoffTruck.LATER, snapshot.pending.getValue(ref).truck)
        assertFalse(service.setTruck("670 - E", HandoffFlagRef("bogus", "x"), HandoffTruck.LATER))
    }
}
```

Add to `TrackerChangeMonitorSpecialtyTest.kt` (reuse that file's existing `waitUntil` helper):

```kotlin
    @Test
    fun adminJsonChangeReportsSpecialtyJob() {
        val baseDir = Files.createTempDirectory("tracker-change-monitor-handoff-test").toFile()
        val jobFolder = "1234 - Test Job"
        val adminDir = File(baseDir, "$jobFolder/.metadata/admin").apply { mkdirs() }
        val handoff = File(adminDir, "handoff.json").apply { writeText("""{"itemFlags":{}}""") }
        val reported = java.util.concurrent.CopyOnWriteArrayList<Set<String>?>()
        val monitor = TrackerChangeMonitor(
            baseDir = baseDir,
            progressStore = ProgressStore(baseDir, "tablet-a", File(baseDir, ".local"), readOnly = true),
            hardwoodsProgressStore = HardwoodsProgressStore(baseDir, "tablet-a", readOnly = true),
            specialtyProgressStore = SpecialtyProgressStore(baseDir, "tablet-a", readOnly = true),
            viewerInteraction = MutableStateFlow(false),
            onSpecialtyJobsChanged = { jobs -> reported += jobs },
            pollingIntervalMs = 100L
        )
        monitor.start()
        try {
            Thread.sleep(1_700L)
            reported.clear()
            handoff.writeText("""{"itemFlags":{"board_stock:x":{"truck":"loose"}}}""")
            waitUntil(timeoutMs = 4_000L) { reported.any { it == null || jobFolder in it } }
        } finally {
            monitor.stop()
        }
    }
```

(If the existing CNC test needed an `activeJobFolderName` to make the job watched, pass the same here: `activeJobFolderName = MutableStateFlow(jobFolder)`.)

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HandoffFlagsServiceTest" --tests "com.kkc.sheettracker.data.TrackerChangeMonitorSpecialtyTest"`
Expected: FAIL (unresolved).

- [ ] **Step 3: Implement service** — `data/HandoffFlagsService.kt`:

```kotlin
package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import java.time.Instant

/**
 * Process-wide "handoff flags may have changed" tick. Bumped by TrackerChangeMonitor (HT rewrote
 * handoff.json / a sidecar changed), by this tablet's own taps and by a REST `skipped` reply.
 * Screens re-read flags when it changes; reading is one small file, so no per-job precision.
 */
object HandoffFlagsChangeSignal {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    fun bump() { _version.value = _version.value + 1 }
}

data class HandoffFlagsSnapshot(
    val stored: Map<HandoffFlagRef, HandoffItemFlags> = emptyMap(),
    val pending: Map<HandoffFlagRef, PendingTruckFlag> = emptyMap(),
    /** Null when HT wasn't reachable: Auto placement is then unknown. */
    val truckList: Map<HandoffFlagRef, HandoffTruckListEntry>? = null
)

data class HandoffTruckDisplay(
    val label: String,
    /** The pinned value (pending wins over stored); null = Auto. */
    val selected: HandoffTruck?,
    val isAuto: Boolean,
    val isPending: Boolean,
    /** Null = unknown (Auto, offline): never draw a ticked or unticked "Ship loose" box. */
    val shipsLoose: Boolean?,
    val countLabel: String?
)

fun handoffTruckDisplay(ref: HandoffFlagRef, snapshot: HandoffFlagsSnapshot): HandoffTruckDisplay {
    val pending = snapshot.pending[ref]
    val stored = snapshot.stored[ref]
    val selected = if (pending != null) pending.truck else stored?.truck
    val countLabel = stored?.count?.label
    if (selected != null) {
        return HandoffTruckDisplay(selected.label, selected, false, pending != null, selected == HandoffTruck.LOOSE, countLabel)
    }
    val entry = snapshot.truckList?.get(ref)
    val (label, shipsLoose) = when {
        entry == null -> "Auto" to null
        entry.excluded -> "Auto · Excluded by office" to false
        entry.effectiveTruck != null -> "Auto · ${entry.effectiveTruck.label}" to (entry.effectiveTruck == HandoffTruck.LOOSE)
        else -> "Auto" to null
    }
    return HandoffTruckDisplay(label, null, true, pending != null, shipsLoose, countLabel)
}

/**
 * One tap = one `requestedAt`, used for BOTH writes:
 * 1. sidecar append (always; the offline path and HT's 30 s pickup),
 * 2. best-effort `PUT …/handoff/flags` in [restScope] (the tap never waits for it).
 */
class HandoffFlagsService(
    private val baseDir: File,
    tabletId: String,
    private val clientProvider: suspend () -> HandoffFlagsClient?,
    private val restScope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC()
) {
    private val requests = HandoffFlagRequestStore(baseDir, tabletId)

    fun loadLocal(jobFolderName: String): HandoffFlagsSnapshot = HandoffFlagsSnapshot(
        stored = loadHandoffItemFlags(baseDir, jobFolderName),
        pending = requests.pendingTruckFlags(jobFolderName, Instant.now(clock))
    )

    suspend fun fetchTruckList(jobFolderName: String): Map<HandoffFlagRef, HandoffTruckListEntry>? =
        clientProvider()?.fetchTruckList(jobFolderName)

    suspend fun setTruck(jobFolderName: String, ref: HandoffFlagRef, truck: HandoffTruck?): Boolean {
        val requestedAt = Instant.now(clock).toString()
        if (!requests.appendTruckRequest(jobFolderName, ref, truck, requestedAt)) return false
        HandoffFlagsChangeSignal.bump()
        restScope.launch {
            when (val result = clientProvider()?.putTruck(jobFolderName, ref, truck, requestedAt)) {
                HandoffFlagPutResult.Skipped -> HandoffFlagsChangeSignal.bump()
                is HandoffFlagPutResult.Failed -> if (result.status == 400 || result.status == 422) {
                    Log.w(TAG, "HT rejected flag body (${result.status}) for ${ref.key} in $jobFolderName — tablet bug")
                }
                is HandoffFlagPutResult.Applied, null -> Unit
            }
        }
        return true
    }

    private companion object { const val TAG = "HandoffFlags" }
}
```

- [ ] **Step 4: TrackerChangeMonitor hook.** Add constructor parameter after `onCncJobsChanged`:

```kotlin
    /** Specialty/admin metadata changed for these jobs (null = all). Used to refresh handoff flags. */
    private val onSpecialtyJobsChanged: ((Set<String>?) -> Unit)? = null,
```

In `applyBatchedInvalidations`, after the `specialtyProgressStore?.let { … }` block and before `scheduleFullRefreshDispatch()`:

```kotlin
        if (invalidateAllSpecialty) {
            onSpecialtyJobsChanged?.invoke(null)
        } else if (specialtyJobs.isNotEmpty()) {
            onSpecialtyJobsChanged?.invoke(specialtyJobs)
        }
```

In `navigation/NavGraph.kt`, in the `TrackerChangeMonitor(` call (~line 262), add `onSpecialtyJobsChanged = { HandoffFlagsChangeSignal.bump() },` and import `com.kkc.sheettracker.data.HandoffFlagsChangeSignal`.

- [ ] **Step 5: Run to verify pass** — same command as Step 2. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/HandoffFlagsService.kt app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/data/HandoffFlagsServiceTest.kt app/src/test/java/com/kkc/sheettracker/data/TrackerChangeMonitorSpecialtyTest.kt
git commit -m "feat(handoff): flag service, display rules and change signal from tracker monitor"
```

---

### Task 12: Truck chip UI on Specialty rows, kanban cards, sheet rips and Hardwoods board stock

**Files:**
- Create: `ui/handoff/HandoffTruckChip.kt`
- Modify: `ui/specialty/SpecialtyJobDetailScreen.kt` (`SpecialtyJobDetailScreen`, `SpecialtyChecklistRow`, `SpecialtySheetRipRow`)
- Modify: `ui/specialty/SpecialtyKanbanBoard.kt` (`SpecialtyKanbanBoard`, `SpecialtyKanbanCard`)
- Modify: `ui/hardwoods/HardwoodsWorkspaceScreen.kt` (`HardwoodsWorkspaceScreen` params + admin board-stock row ~line 2841)
- Modify: `navigation/NavGraph.kt` (both `HardwoodsWorkspaceScreen(` calls ~2105 and ~3630: pass `tabletId = tabletId`)
- Modify: `navigation/ArchiveJobDetailHost.kt` (`HardwoodsWorkspaceScreen(` call: pass `handoffEditable = false`)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/handoff/HandoffFlagsWiringTest.kt`

**Interfaces:**
- Consumes: Task 8 `handoffFlagRef`, Task 11 service/display/signal, `AdminSyncConfig`.
- Produces:
  - `class HandoffFlagsUiState(snapshot: HandoffFlagsSnapshot, editable: Boolean, onSelect: (HandoffFlagRef, HandoffTruck?) -> Unit) { fun uiFor(ref: HandoffFlagRef): HandoffItemUi }`
  - `data class HandoffItemUi(val display: HandoffTruckDisplay, val onSelectTruck: ((HandoffTruck?) -> Unit)?)`
  - `@Composable fun rememberHandoffFlags(basePath: String, jobFolderName: String, tabletId: String, editable: Boolean): HandoffFlagsUiState`
  - `@Composable fun HandoffTruckChip(ui: HandoffItemUi, modifier: Modifier = Modifier)`
  - New optional params: `SpecialtyChecklistRow(…, handoff: HandoffItemUi? = null)`, `SpecialtySheetRipRow(…, handoff: HandoffItemUi? = null)`, `SpecialtyKanbanCard(…, handoff: HandoffItemUi? = null)`, `SpecialtyKanbanBoard(…, handoffFor: (SpecialtyItem) -> HandoffItemUi? = { null })`, `HardwoodsWorkspaceScreen(…, tabletId: String = "", handoffEditable: Boolean = true)`.

- [ ] **Step 1: Write the wiring test** — `ui/handoff/HandoffFlagsWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.handoff

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffFlagsWiringTest {
    @Test
    fun specialtyDetailShowsChipsOnRowsKanbanAndSheetRips() {
        val src = source("ui/specialty/SpecialtyJobDetailScreen.kt")
        assertTrue(src.contains("rememberHandoffFlags("))
        assertTrue(src.contains("editable = !readOnly"))
        assertTrue(src.contains("handoff = handoffFlags.uiFor(handoffFlagRef(resolved.item))"))
        assertTrue(src.contains("handoff = handoffFlags.uiFor(handoffFlagRef(rip))"))
        assertTrue(src.contains("handoffFor = { item -> handoffFlags.uiFor(handoffFlagRef(item)) }"))
    }

    @Test
    fun kanbanCardRendersChip() {
        assertTrue(source("ui/specialty/SpecialtyKanbanBoard.kt").contains("HandoffTruckChip("))
    }

    @Test
    fun hardwoodsBoardStockShowsChipAndArchiveIsReadOnly() {
        val hw = source("ui/hardwoods/HardwoodsWorkspaceScreen.kt")
        assertTrue(hw.contains("rememberHandoffFlags("))
        assertTrue(hw.contains("handoffFlags.uiFor(handoffFlagRef(item))"))
        assertTrue(source("navigation/ArchiveJobDetailHost.kt").contains("handoffEditable = false"))
    }

    @Test
    fun nothingEverWritesHandoffJson() {
        listOf("data/HandoffFlags.kt", "data/HandoffFlagRequestStore.kt", "data/HandoffFlagsService.kt", "ui/handoff/HandoffTruckChip.kt").forEach { path ->
            val src = source(path)
            assertFalse(path, Regex("""atomicWriteFile\(\s*handoffJsonFile""").containsMatchIn(src))
            assertFalse(path, src.contains("handoffJsonFile(") && src.contains("writeText"))
        }
    }

    private fun source(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relativePath")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relativePath")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Missing source $relativePath")
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.handoff.HandoffFlagsWiringTest"`
Expected: FAIL.

- [ ] **Step 3: Create the chip + state** — `ui/handoff/HandoffTruckChip.kt`:

```kotlin
package com.kkc.sheettracker.ui.handoff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.HandoffFlagsChangeSignal
import com.kkc.sheettracker.data.HandoffFlagsClient
import com.kkc.sheettracker.data.HandoffFlagsService
import com.kkc.sheettracker.data.HandoffFlagsSnapshot
import com.kkc.sheettracker.data.HandoffTruckDisplay
import com.kkc.sheettracker.data.handoffTruckDisplay
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HandoffItemUi(
    val display: HandoffTruckDisplay,
    /** Null on read-only screens. */
    val onSelectTruck: ((HandoffTruck?) -> Unit)?
)

class HandoffFlagsUiState(
    private val snapshot: HandoffFlagsSnapshot,
    private val editable: Boolean,
    private val onSelect: (HandoffFlagRef, HandoffTruck?) -> Unit
) {
    fun uiFor(ref: HandoffFlagRef): HandoffItemUi = HandoffItemUi(
        display = handoffTruckDisplay(ref, snapshot),
        onSelectTruck = if (editable) ({ truck -> onSelect(ref, truck) }) else null
    )
}

/** REST writes outlive the screen; the sidecar already guarantees delivery either way. */
private val handoffRestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Minimum gap between truck-list GETs per screen (HT applies pending requests on each GET). */
private const val TRUCK_LIST_MIN_INTERVAL_MS = 15_000L

@Composable
fun rememberHandoffFlags(basePath: String, jobFolderName: String, tabletId: String, editable: Boolean): HandoffFlagsUiState {
    val context = LocalContext.current
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val service = remember(basePath, tabletId) {
        HandoffFlagsService(
            baseDir = File(basePath),
            tabletId = tabletId,
            clientProvider = { adminSyncConfig.getServerUrl()?.let(::HandoffFlagsClient) },
            restScope = handoffRestScope
        )
    }
    val signal by HandoffFlagsChangeSignal.version.collectAsState()
    // Pending entries expire after 120 s; tick so an expired one stops showing without a file change.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    var truckList by remember(jobFolderName) { mutableStateOf<Map<HandoffFlagRef, HandoffTruckListEntry>?>(null) }
    var lastFetchMs by remember(jobFolderName) { mutableLongStateOf(0L) }
    LaunchedEffect(jobFolderName, signal) {
        val wait = TRUCK_LIST_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFetchMs)
        if (wait > 0) delay(wait)
        lastFetchMs = System.currentTimeMillis()
        // Keep the last good list when HT is unreachable rather than flipping every Auto back to unknown.
        service.fetchTruckList(jobFolderName)?.let { truckList = it }
    }
    val local by produceState(HandoffFlagsSnapshot(), basePath, jobFolderName, signal, tick) {
        value = withContext(Dispatchers.IO) { service.loadLocal(jobFolderName) }
    }
    val scope = rememberCoroutineScope()
    return remember(local, truckList, editable, service, jobFolderName) {
        HandoffFlagsUiState(local.copy(truckList = truckList), editable) { ref, truck ->
            scope.launch(Dispatchers.IO) { service.setTruck(jobFolderName, ref, truck) }
        }
    }
}

private val TRUCK_MENU: List<Pair<HandoffTruck?, String>> =
    listOf(null to "Auto (office rules)") + HandoffTruck.entries.map { it to it.label }

@Composable
fun HandoffTruckChip(ui: HandoffItemUi, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val display = ui.display
    val text = buildString {
        append(display.label)
        if (display.isPending) append(" · pending")
        display.countLabel?.let { append(" · ").append(it) }
    }
    Box(modifier) {
        AssistChip(
            onClick = { if (ui.onSelectTruck != null) menuOpen = true },
            label = { Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.LocalShipping, contentDescription = "Delivery handoff", modifier = Modifier.size(16.dp)) }
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            TRUCK_MENU.forEach { (truck, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { menuOpen = false; ui.onSelectTruck?.invoke(truck) },
                    trailingIcon = if (truck == display.selected) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                )
            }
        }
    }
}
```

- [ ] **Step 4: Specialty detail screen.**
  1. In `SpecialtyJobDetailScreen` after `val resolvedItems = loadedChecklist.items` add:

```kotlin
    val handoffFlags = rememberHandoffFlags(
        basePath = scanState.snapshot.basePath,
        jobFolderName = jobFolderName,
        tabletId = tabletId,
        editable = !readOnly
    )
```

  2. Each `SpecialtySheetRipRow(` call (lines ~452 and ~546): add `handoff = handoffFlags.uiFor(handoffFlagRef(rip)),` (use the lambda's variable name at the second site; rename it to `rip` if it differs so the wiring test string matches).
  3. The `SpecialtyChecklistRow(` call at ~642: add `handoff = handoffFlags.uiFor(handoffFlagRef(resolved.item)),` (rename the loop variable to `resolved` if it differs).
  4. `SpecialtyKanbanBoard(` call at ~444: add `handoffFor = { item -> handoffFlags.uiFor(handoffFlagRef(item)) },`.
  5. `SpecialtyChecklistRow` signature: add `handoff: HandoffItemUi? = null` as the last parameter. In the expanded (non-collapsed) body, directly above where `SpecialtyItemDetails(` is invoked, add `handoff?.let { HandoffTruckChip(it) }`. Do not show it in `CollapsedSpecialtyChecklistRow`.
  6. `SpecialtySheetRipRow` signature: add `handoff: HandoffItemUi? = null`; inside its content `Row`, before the trailing done indicator, add `handoff?.let { HandoffTruckChip(it, Modifier.padding(start = 6.dp)) }`. The chip's own click opens the menu and does not toggle done (AssistChip consumes the click).
  7. Imports: `com.kkc.sheettracker.data.handoffFlagRef`, `com.kkc.sheettracker.ui.handoff.HandoffItemUi`, `com.kkc.sheettracker.ui.handoff.HandoffTruckChip`, `com.kkc.sheettracker.ui.handoff.rememberHandoffFlags`.

- [ ] **Step 5: Kanban.** `SpecialtyKanbanBoard(…, handoffFor: (SpecialtyItem) -> HandoffItemUi? = { null })` and pass `handoff = handoffFor(resolved.item)` into every `SpecialtyKanbanCard(` it builds. `SpecialtyKanbanCard(…, handoff: HandoffItemUi? = null)`: render `handoff?.let { HandoffTruckChip(it) }` on its own line under the title/steps summary (outside the dimmed summary so it stays readable). Imports as above.

- [ ] **Step 6: Hardwoods.** `HardwoodsWorkspaceScreen` params: add `tabletId: String = ""` and `handoffEditable: Boolean = true` after `pdfMarkupReadOnly`. After the `adminBoardStock` `produceState` add:

```kotlin
    val handoffFlags = rememberHandoffFlags(
        basePath = scanState.snapshot.basePath,
        jobFolderName = jobFolderName,
        tabletId = tabletId,
        editable = handoffEditable && !pdfMarkupReadOnly && tabletId.isNotBlank()
    )
```

`adminBoardStock` lives in `HardwoodsWorkspaceScreen`, but the rows render in the composable that takes `adminItems: List<AdminBoardStockItem>` (~line 2687). Add a parameter there: `handoffFor: (AdminBoardStockItem) -> HandoffItemUi? = { null }`, pass `handoffFor = { item -> handoffFlags.uiFor(handoffFlagRef(item)) }` at its call site (~1505), and in the admin entry `Column` (~2861) add as its first child `handoffFor(item)?.let { HandoffTruckChip(it) }`. Then rename the call-site lambda so the literal `handoffFlags.uiFor(handoffFlagRef(item))` appears (wiring test). In `NavGraph.kt` both `HardwoodsWorkspaceScreen(` calls get `tabletId = tabletId,`; in `ArchiveJobDetailHost.kt` add `handoffEditable = false,`.

- [ ] **Step 7: Run tests + compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.handoff.*" --tests "com.kkc.sheettracker.ui.specialty.*" --tests "com.kkc.sheettracker.ui.hardwoods.*" --tests "com.kkc.sheettracker.navigation.*"`
Expected: PASS. Then `.\gradlew.bat :app:compileDebugKotlin` — BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/handoff/HandoffTruckChip.kt app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/navigation/ArchiveJobDetailHost.kt app/src/test/java/com/kkc/sheettracker/ui/handoff/HandoffFlagsWiringTest.kt
git commit -m "feat(handoff): truck flag chip on specialty rows, kanban, sheet rips and board stock"
```

---

# Part C — Release + docs

### Task 13: Version, full verification, HT docs, manual checks

**Files:**
- Modify: `app/build.gradle.kts:23-24`
- Modify (docs only, HT repo — the handoffs explicitly ask for this): `C:\Scripts\Hours Tracker\METADATA_CONTRACT.md` §2.3 and §2.12, `C:\Scripts\Hours Tracker\METADATA_AUDIT.md` (change-log row), `C:\Users\chadc\.sync-skills\skills\kkc-metadata-map\SKILL.md`

- [ ] **Step 1: Bump version** — `versionCode = 80800`, `versionName = "8.8.0"`.

- [ ] **Step 2: Full unit test run**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all pass except the known off-device `PdfMarkup` MotionEvent test (env-only, documented in memory). Any other failure: stop and fix.

- [ ] **Step 3: Release build**

Run: `.\gradlew.bat assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: 8.8.0 — dated delivery schedule and handoff flags"
```

- [ ] **Step 5: HT docs.** In `METADATA_CONTRACT.md` §2.12 replace "tablet side not yet built" with the KST owners: reader `data/HandoffFlags.kt` (`loadHandoffItemFlags`, `handoffFlagRef`), sidecar `data/HandoffFlagRequestStore.kt`, REST `data/HandoffFlagsClient.kt`, orchestration `data/HandoffFlagsService.kt`, UI `ui/handoff/HandoffTruckChip.kt`; tablet edits `truck` only, any station. In §2.3 replace the tablet-side notes with: parser `parseDeliveryScheduleDocument` (`data/DeliveryScheduleRepository.kt`), week logic `data/DeliveryWeek.kt`, edits `data/DeliveryScheduleEditor.kt` (PUT → admin-sync → request file), request store `data/DeliveryScheduleRequestStore.kt` (Mutex; L-06 fixed). Add a `METADATA_AUDIT.md` change-log row dated 2026-10-09 naming KST 8.8.0 and both contracts (and L-06 resolved). Update the `kkc-metadata-map` skill rows for `delivery_schedule.json`, `delivery_schedule_request.<tabletId>.json`, `handoff.json`, `handoff_flags_request.<tabletId>.json` with the same KST file names. Commit in the HT repo only those doc files (`git -C "C:\Scripts\Hours Tracker" add METADATA_CONTRACT.md METADATA_AUDIT.md` then commit `docs: KST 8.8.0 consumes dated delivery schedule and handoff flags`) — ask the user before committing in HT.

- [ ] **Step 6: Deploy + manual checks (with the user).** Deploy with `.\gradlew.bat assembleRelease` then `adb install -r app\build\outputs\apk\release\app-release.apk` per connected tablet (the `adb-install-release.ps1` script has a unicode issue under `powershell -File`). Ask the user which job and dates to use; ask them to navigate to each screen and confirm. Dated delivery checks require HT's `worktree-office-kanban` branch deployed — confirm with the user first; until then verify only that v1 behavior is unchanged.
  - Delivery: (1) future-week date set in HT web shows under the right date after navigating forward and not in the banner; (2) schedule a job onto a dated slot from the tablet → HT Office job shows the date + tablet note; (3) remove it → Office date cleared; (4) fourth job into a full half-day → tablet notice; (5) queue offline, reconnect → lands within ~15 s, Pending clears; (6) on a Saturday the banner reads "Deliveries Next Week" with future days.
  - Flags: (1) set Ship loose in HT → tablet chip shows "Ship loose"; (2) change on tablet online → HT Truck list shows it; (3) change offline, reconnect → lands within ~30 s or when the office opens the job; (4) change `count` in the office then `truck` on the tablet → both survive.
