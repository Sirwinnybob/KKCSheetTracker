# Settings Facelift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-scroll Settings screen with a pinned work-mode tile row, a sectioned rail + detail pane, status chips, and a new KKC icon set — without changing any setting's storage or the screen's public API.

**Architecture:** `SettingsScreen(...)` keeps its exact signature and becomes a thin shell (top bar + chips, `WorkModeRow`, `SettingsRail`, scrolling pane). Pure decision logic lives in `SettingsSection.kt` / `SettingsLogic.kt` (JVM-tested); reusable composables live in `SettingsChrome.kt`; each of the 8 sections is its own file under `ui/settings/panes/`. Icons follow the existing `IconDsl.kt` family.

**Tech Stack:** Kotlin, Jetpack Compose Material3, JUnit 4 (JVM unit tests only — composables are guarded by source-reading wiring tests, the project's established pattern).

**Spec:** `docs/superpowers/specs/2026-10-08-settings-facelift-design.md`

## Global Constraints

- `SettingsScreen(...)` public signature must stay byte-for-byte identical (NavGraph and `FlexibleModeWiringTest` / `PendingUpdatesSettingsWiringTest` depend on it). Do not touch `navigation/NavGraph.kt`.
- Mode tile height = `min(tileWidth, 168.dp)`; logo canvas square, 52% of tile height.
- Wide chips (Tablet, Version) only when screen width ≥ 1000dp.
- Rail width 240dp; rail and pane each scroll; both keep 160dp bottom clearance for the floating navbar.
- Never `Surface(shadowElevation)` or semi-transparent fill + shadow (CLAUDE.md). Use `Modifier.kkcCardDepth(shape, elevation = …)` then `.background(solidColor)`.
- Settings root must keep painting `MaterialTheme.colorScheme.background` (Scaffold default) — the transparent navbar's Haze source depends on it.
- Icons: 24×24 viewport, stroke 2 (1.5 where noted), built only with `IconDsl.kt` helpers. Unselected = outline; Selected = adds `DUOTONE` body fill.
- Old private helpers in `SettingsScreen.kt` (`SettingsCard`, `WorkModeIconTile`, `SettingsStatusBadge`, `filledFieldColors`, `ToggleRow`, `formatStatusTime`) stay until Task 7 deletes them, so new top-level names must not collide: use the names given in this plan.
- Edit files with Edit/Write tools, not sed/python (user preference).
- Work on branch `feat/settings-facelift`, never `main`. Commit steps run only if the user approved committing for this execution; otherwise skip them and leave changes staged-ready.
- Build/test from PowerShell in `C:\Scripts\KKCSheetTracker`. Known env-only failure: one `PdfMarkup` MotionEvent unit test fails off-device — not a regression.

## Review Focus

1. Whitespace-only Tablet ID or Ready Jobs path → Save stays disabled (never saves blank). Pinned in Task 1 (`saveButtonState` test).
2. Timeclock / Admin Sync IP cleared to blank or spaces → stored as `null` (mDNS / fast path off), not `""`. Pinned in Task 1 (`storedIpOrNull` test).
3. Theme override id no longer in the catalog → swatch highlight falls back to the active theme instead of highlighting nothing. Pinned in Task 1 (`selectedThemeId` test).
4. Active theme is an NFL team → the NFL card shows that team's name (and is highlighted) instead of "NFL team…". Pinned in Task 1 (`nflCardLabel` test).
5. Saved value arrives/changes after the screen opens (IP flows emit after first frame) and rotation mid-edit → field shows the new saved value, unsaved edits survive rotation. Pinned in Task 3 (source test: `rememberSaveable(savedValue)` in `SaveableField`).

---

## File Map

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSection.kt` | Create | Section enum + layout decisions |
| `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsLogic.kt` | Create | Chip/field/theme pure helpers |
| `app/src/main/java/com/kkc/sheettracker/ui/components/icons/SettingsIcons.kt` | Create | 8 section icons × 2 states |
| `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSectionIcons.kt` | Create | `SettingsSection.icon(selected)` |
| `app/src/main/java/com/kkc/sheettracker/ui/settings/WorkModeArt.kt` | Create | Mode logo swap point + display names |
| `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsChrome.kt` | Create | Shared composables |
| `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/*.kt` | Create (8) | One pane per section |
| `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` | Rewrite body | Shell; signature unchanged |
| `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsSectionTest.kt` | Create | Task 1 |
| `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsLogicTest.kt` | Create | Task 1 |
| `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsIconsTest.kt` | Create | Task 2 |
| `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsChromeWiringTest.kt` | Create | Task 3 |
| `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsPanesWiringTest.kt` | Create | Task 7 |
| `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenPendingUpdatesWiringTest.kt` | Modify | Task 7 (card moved to pane) |
| `app/build.gradle.kts` | Modify | Task 8 version bump |

---

### Task 1: Section model and pure logic

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSection.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsLogic.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsSectionTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsLogicTest.kt`

**Interfaces:**
- Consumes: `SyncthingServiceStatus` (`com.kkc.sheettracker.sync`), `KKCThemeCatalog`, `KKCThemeDefinition` (`com.kkc.sheettracker.ui.theme`), `footballTeamThemes` (`ThemePickerLogic.kt`).
- Produces:
  - `enum class SettingsSection(val title: String, val subtitle: String, val isAdvanced: Boolean)` with entries `LOOK_AND_FEEL, VIEWERS, ME, UPDATES_ABOUT, TABLET_DATA, SYNC_NETWORK, PERFORMANCE_POWER, ADMIN`
  - `internal fun initialSection(hasPendingUpdates: Boolean): SettingsSection`
  - `internal fun showWideChips(widthDp: Float): Boolean`
  - `internal fun modeTileHeight(tileWidth: Dp): Dp`
  - `enum class ChipTone { OK, WARN, BAD }`
  - `internal fun syncChip(status: SyncthingServiceStatus): Pair<ChipTone, String>`
  - `internal fun pendingUpdateCount(hasSelfUpdate: Boolean, externalCount: Int): Int`
  - `internal fun updatesChipLabel(count: Int): String`
  - `data class SaveButtonState(val visible: Boolean, val enabled: Boolean)`
  - `internal fun saveButtonState(edit: String, saved: String, allowBlank: Boolean): SaveButtonState`
  - `internal fun storedIpOrNull(text: String): String?`
  - `internal fun selectedThemeId(catalog: KKCThemeCatalog): String`
  - `internal fun nflCardLabel(catalog: KKCThemeCatalog): String`
  - `internal fun themeSwatch(theme: KKCThemeDefinition, dark: Boolean): Pair<Color, Color>`

- [ ] **Step 1: Create the branch**

```powershell
git switch -c feat/settings-facelift
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsSectionTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionTest {

    @Test
    fun sectionsAreInRailOrderWithFourAdvancedAtTheEnd() {
        assertEquals(
            listOf("LOOK_AND_FEEL", "VIEWERS", "ME", "UPDATES_ABOUT", "TABLET_DATA", "SYNC_NETWORK", "PERFORMANCE_POWER", "ADMIN"),
            SettingsSection.entries.map { it.name }
        )
        assertEquals(listOf(false, false, false, false, true, true, true, true), SettingsSection.entries.map { it.isAdvanced })
    }

    @Test
    fun everySectionHasTitleAndSubtitle() {
        SettingsSection.entries.forEach {
            assertTrue(it.name, it.title.isNotBlank())
            assertTrue(it.name, it.subtitle.isNotBlank())
        }
    }

    @Test
    fun opensOnUpdatesOnlyWhenUpdatesArePending() {
        assertEquals(SettingsSection.UPDATES_ABOUT, initialSection(hasPendingUpdates = true))
        assertEquals(SettingsSection.LOOK_AND_FEEL, initialSection(hasPendingUpdates = false))
    }

    @Test
    fun wideChipsStartAt1000dp() {
        assertFalse(showWideChips(999f))
        assertTrue(showWideChips(1000f))
        assertFalse("portrait shop tablet", showWideChips(824f))
        assertTrue("landscape shop tablet", showWideChips(1318f))
    }

    @Test
    fun modeTileIsSquareUntil168dpThenCapped() {
        assertEquals(150.dp, modeTileHeight(150.dp))
        assertEquals(168.dp, modeTileHeight(168.dp))
        assertEquals(168.dp, modeTileHeight(315.dp))
    }
}
```

`app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsLogicTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.ui.theme.BuiltInKKCThemeTokens
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.theme.KKCThemeDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {

    private fun theme(id: String, name: String, category: String = "custom") =
        KKCThemeDefinition(id = id, name = name, version = 1, category = category, tokens = BuiltInKKCThemeTokens)

    private val default = theme("kkc-default", "KKC Default")
    private val forest = theme("kkc-forest", "KKC Forest")
    private val chiefs = theme("nfl-chiefs", "Kansas City Chiefs", "nfl")

    private fun catalog(active: KKCThemeDefinition = default, override: String? = null) = KKCThemeCatalog(
        themes = listOf(default, forest, chiefs),
        activeTheme = active,
        syncedDefaultThemeId = null,
        invalidThemes = emptyList(),
        loadMessages = emptyList(),
        followSyncedDefault = override == null,
        overrideThemeId = override
    )

    @Test
    fun syncChipMapsEveryStatus() {
        assertEquals(ChipTone.OK to "Sync running", syncChip(SyncthingServiceStatus.RUNNING))
        assertEquals(ChipTone.WARN to "Sync checking", syncChip(SyncthingServiceStatus.CHECKING))
        assertEquals(ChipTone.WARN to "Sync paused", syncChip(SyncthingServiceStatus.PAUSED))
        assertEquals(ChipTone.WARN to "Sync key needed", syncChip(SyncthingServiceStatus.API_KEY_REQUIRED))
        assertEquals(ChipTone.BAD to "Sync stopped", syncChip(SyncthingServiceStatus.NOT_RUNNING))
        assertEquals(ChipTone.BAD to "Sync failed", syncChip(SyncthingServiceStatus.START_FAILED))
    }

    @Test
    fun updateCountAndLabel() {
        assertEquals(0, pendingUpdateCount(false, 0))
        assertEquals(3, pendingUpdateCount(true, 2))
        assertEquals("1 update", updatesChipLabel(1))
        assertEquals("3 updates", updatesChipLabel(3))
    }

    @Test
    fun saveButtonHiddenWhenUnchangedIgnoringSurroundingSpaces() {
        assertEquals(SaveButtonState(visible = false, enabled = false), saveButtonState("CNC-2 ", "CNC-2", allowBlank = false))
        assertEquals(SaveButtonState(visible = true, enabled = true), saveButtonState("CNC-3", "CNC-2", allowBlank = false))
    }

    @Test
    fun whitespaceOnlyValueCannotBeSavedUnlessBlankAllowed() {
        assertEquals(SaveButtonState(visible = true, enabled = false), saveButtonState("   ", "CNC-2", allowBlank = false))
        assertEquals(SaveButtonState(visible = true, enabled = true), saveButtonState("   ", "10.0.0.5", allowBlank = true))
    }

    @Test
    fun blankIpIsStoredAsNull() {
        assertNull(storedIpOrNull(""))
        assertNull(storedIpOrNull("   "))
        assertEquals("10.0.0.5", storedIpOrNull(" 10.0.0.5 "))
    }

    @Test
    fun selectedThemeIsOverrideWhenPresentElseActive() {
        assertEquals("kkc-forest", selectedThemeId(catalog(override = "kkc-forest")))
        assertEquals("kkc-default", selectedThemeId(catalog(override = null)))
    }

    @Test
    fun overrideMissingFromCatalogFallsBackToActiveTheme() {
        assertEquals("kkc-default", selectedThemeId(catalog(override = "deleted-theme")))
    }

    @Test
    fun nflCardShowsActiveTeamName() {
        assertEquals("NFL team…", nflCardLabel(catalog(override = "kkc-forest")))
        assertEquals("Kansas City Chiefs", nflCardLabel(catalog(override = "nfl-chiefs")))
        assertEquals("Kansas City Chiefs", nflCardLabel(catalog(active = chiefs, override = null)))
    }

    @Test
    fun swatchUsesPaletteForMode() {
        assertEquals(BuiltInKKCThemeTokens.light.primary to BuiltInKKCThemeTokens.light.secondary, themeSwatch(default, dark = false))
        assertEquals(BuiltInKKCThemeTokens.dark.primary to BuiltInKKCThemeTokens.dark.secondary, themeSwatch(default, dark = true))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.SettingsSectionTest" --tests "com.kkc.sheettracker.ui.settings.SettingsLogicTest"`
Expected: FAIL — compilation errors `Unresolved reference: SettingsSection`, `initialSection`, `syncChip`, etc.

- [ ] **Step 4: Implement**

`app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSection.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Settings rail sections, in rail order. Advanced ones are grouped under an "Advanced" caption. */
enum class SettingsSection(val title: String, val subtitle: String, val isAdvanced: Boolean) {
    LOOK_AND_FEEL("Look & Feel", "Theme and light/dark for this tablet", false),
    VIEWERS("Viewers", "How sheets and references display", false),
    ME("Me", "Who is using this tablet", false),
    UPDATES_ABOUT("Updates & About", "App version and pending installs", false),
    TABLET_DATA("Tablet & Data", "Identity and data folder", true),
    SYNC_NETWORK("Sync & Network", "Syncthing, timeclock hub, Hours Tracker", true),
    PERFORMANCE_POWER("Performance & Power", "Low-end mode and idle power saving", true),
    ADMIN("Admin", "Advanced controls for the office", true),
}

/** Landscape cap so the rail still fits above the floating navbar; portrait tiles stay square. */
internal val MODE_TILE_MAX_HEIGHT: Dp = 168.dp
internal const val WIDE_CHIPS_MIN_WIDTH_DP = 1000f

internal fun initialSection(hasPendingUpdates: Boolean): SettingsSection =
    if (hasPendingUpdates) SettingsSection.UPDATES_ABOUT else SettingsSection.LOOK_AND_FEEL

internal fun showWideChips(widthDp: Float): Boolean = widthDp >= WIDE_CHIPS_MIN_WIDTH_DP

internal fun modeTileHeight(tileWidth: Dp): Dp = minOf(tileWidth, MODE_TILE_MAX_HEIGHT)
```

`app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsLogic.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.theme.KKCThemeDefinition

enum class ChipTone { OK, WARN, BAD }

internal fun syncChip(status: SyncthingServiceStatus): Pair<ChipTone, String> = when (status) {
    SyncthingServiceStatus.RUNNING -> ChipTone.OK to "Sync running"
    SyncthingServiceStatus.CHECKING -> ChipTone.WARN to "Sync checking"
    SyncthingServiceStatus.PAUSED -> ChipTone.WARN to "Sync paused"
    SyncthingServiceStatus.API_KEY_REQUIRED -> ChipTone.WARN to "Sync key needed"
    SyncthingServiceStatus.NOT_RUNNING -> ChipTone.BAD to "Sync stopped"
    SyncthingServiceStatus.START_FAILED -> ChipTone.BAD to "Sync failed"
}

internal fun pendingUpdateCount(hasSelfUpdate: Boolean, externalCount: Int): Int =
    (if (hasSelfUpdate) 1 else 0) + externalCount

internal fun updatesChipLabel(count: Int): String = if (count == 1) "1 update" else "$count updates"

data class SaveButtonState(val visible: Boolean, val enabled: Boolean)

/** Save shows once the trimmed edit differs from what's stored; blank saves only where allowed. */
internal fun saveButtonState(edit: String, saved: String, allowBlank: Boolean): SaveButtonState {
    val trimmed = edit.trim()
    val dirty = trimmed != saved.trim()
    return SaveButtonState(visible = dirty, enabled = dirty && (allowBlank || trimmed.isNotEmpty()))
}

/** Server IP fields: blank means "auto / not configured", which the configs store as null. */
internal fun storedIpOrNull(text: String): String? = text.trim().ifBlank { null }

internal fun selectedThemeId(catalog: KKCThemeCatalog): String =
    catalog.overrideThemeId?.takeIf { id -> catalog.themes.any { it.id == id } } ?: catalog.activeTheme.id

internal fun nflCardLabel(catalog: KKCThemeCatalog): String {
    val id = selectedThemeId(catalog)
    return footballTeamThemes(catalog.themes).firstOrNull { it.id == id }?.name ?: "NFL team…"
}

internal fun themeSwatch(theme: KKCThemeDefinition, dark: Boolean): Pair<Color, Color> {
    val palette = if (dark) theme.tokens.dark else theme.tokens.light
    return palette.primary to palette.secondary
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.SettingsSectionTest" --tests "com.kkc.sheettracker.ui.settings.SettingsLogicTest"`
Expected: PASS (14 tests).

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSection.kt app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsLogic.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsSectionTest.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsLogicTest.kt
git commit -m "feat(settings): add section model and pure settings logic"
```

---

### Task 2: Settings section icons

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/icons/SettingsIcons.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSectionIcons.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsIconsTest.kt`

**Interfaces:**
- Consumes: `IconDsl.kt` — `kkcIcon(name) {}`, `line(width, alpha) {}`, `solid(alpha) {}`, `block(width) {}`, `PathBuilder.roundRect(l,t,r,b,rad)`, `PathBuilder.circle(cx,cy,r)`, `DUOTONE`; `SettingsSection` (Task 1).
- Produces: public vals `SettingsLookFeelSelected/Unselected`, `SettingsViewersSelected/Unselected`, `SettingsMeSelected/Unselected`, `SettingsUpdatesSelected/Unselected`, `SettingsTabletDataSelected/Unselected`, `SettingsSyncNetworkSelected/Unselected`, `SettingsPerformanceSelected/Unselected`, `SettingsAdminSelected/Unselected` (all `ImageVector`); `internal fun SettingsSection.icon(selected: Boolean): ImageVector`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsIconsTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIconsTest {

    private val all = SettingsSection.entries.flatMap { listOf(it.icon(true), it.icon(false)) }

    @Test
    fun selectedAndUnselectedDifferForEverySection() {
        SettingsSection.entries.forEach {
            assertNotEquals(it.name, it.icon(true).name, it.icon(false).name)
        }
    }

    @Test
    fun iconsBelongToTheSettingsFamily() {
        all.forEach { assertTrue(it.name, it.name.startsWith("Settings")) }
    }

    @Test
    fun noTwoSectionsShareAnIcon() {
        val names = all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun allIconsUseThe24UnitViewport() {
        all.forEach {
            assertEquals(it.name, 24f, it.viewportWidth)
            assertEquals(it.name, 24f, it.viewportHeight)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.SettingsIconsTest"`
Expected: FAIL — `Unresolved reference: icon`.

- [ ] **Step 3: Implement the icons**

`app/src/main/java/com/kkc/sheettracker/ui/components/icons/SettingsIcons.kt`:

```kotlin
package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Settings rail section icons. Same rule as the navbar:
//   Unselected: outline only. Selected: duotone body; solid details stay solid in both states.
// Style and helpers live in IconDsl.kt.

private fun state(selected: Boolean) = if (selected) "selected" else "unselected"

// ── Look & Feel: paint palette with four solid paint wells ──────────────────────
private fun lookFeel(selected: Boolean) = kkcIcon("SettingsLookFeel.${state(selected)}") {
    val body: PathBuilder.() -> Unit = {
        moveTo(12f, 3f)
        curveTo(7f, 3f, 3f, 6.8f, 3f, 11.5f)
        reflectiveCurveTo(6.8f, 20f, 11.5f, 20f)
        curveTo(13f, 20f, 13.5f, 19f, 13f, 18f)
        curveTo(12.4f, 16.8f, 13.2f, 15.5f, 14.5f, 15.5f)
        horizontalLineTo(16.5f)
        curveTo(19f, 15.5f, 21f, 13.5f, 21f, 11f)
        curveTo(21f, 6.6f, 17f, 3f, 12f, 3f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = body)
    line(pathBuilder = body)
    solid {
        circle(7.5f, 11.5f, 1.3f)
        circle(9.5f, 7.3f, 1.3f)
        circle(14.5f, 7f, 1.3f)
        circle(17.3f, 10.5f, 1.3f)
    }
}

// ── Viewers: sheet with an eye ──────────────────────────────────────────────────
private fun viewers(selected: Boolean) = kkcIcon("SettingsViewers.${state(selected)}") {
    if (selected) solid(DUOTONE) { roundRect(4.5f, 2f, 19.5f, 22f, 2f) }
    line { roundRect(4.5f, 2f, 19.5f, 22f, 2f) }
    line { moveTo(8f, 6.5f); horizontalLineTo(13f) }
    line(width = 1.5f) {
        moveTo(7.5f, 14.5f)
        quadTo(12f, 9.5f, 16.5f, 14.5f)
        quadTo(12f, 19.5f, 7.5f, 14.5f)
        close()
    }
    solid { circle(12f, 14.5f, 1.6f) }
}

// ── Me: ID badge on a clip ──────────────────────────────────────────────────────
private fun me(selected: Boolean) = kkcIcon("SettingsMe.${state(selected)}") {
    if (selected) solid(DUOTONE) { roundRect(3.5f, 5f, 20.5f, 21f, 2f) }
    line { roundRect(3.5f, 5f, 20.5f, 21f, 2f) }
    block(width = 1.5f) { roundRect(10f, 2.5f, 14f, 6.5f, 1f) }
    line(width = 1.5f) {
        circle(9f, 12f, 2f)
        moveTo(6f, 17.5f)
        curveTo(6f, 15.8f, 7.3f, 14.8f, 9f, 14.8f)
        reflectiveCurveTo(12f, 15.8f, 12f, 17.5f)
    }
    line {
        moveTo(15f, 12f); horizontalLineTo(17.5f)
        moveTo(15f, 15.5f); horizontalLineTo(17.5f)
    }
}

// ── Updates & About: refresh loop with a download arrow ─────────────────────────
private fun updates(selected: Boolean) = kkcIcon("SettingsUpdates.${state(selected)}") {
    if (selected) solid(DUOTONE) { circle(12f, 12f, 8f) }
    line {
        moveTo(20f, 12f)
        arcTo(8f, 8f, 0f, true, true, 17.7f, 6.3f)
        moveTo(18.5f, 2.5f); verticalLineTo(6.5f); horizontalLineTo(14.5f)
        moveTo(12f, 7.5f); verticalLineTo(15.5f)
        moveTo(9f, 12.5f); lineTo(12f, 15.5f); lineTo(15f, 12.5f)
    }
}

// ── Tablet & Data: tablet with a folder in front ────────────────────────────────
private fun tabletData(selected: Boolean) = kkcIcon("SettingsTabletData.${state(selected)}") {
    line {
        moveTo(13f, 21f)
        horizontalLineTo(5f)
        arcTo(2f, 2f, 0f, false, true, 3f, 19f)
        verticalLineTo(5f)
        arcTo(2f, 2f, 0f, false, true, 5f, 3f)
        horizontalLineTo(13f)
        arcTo(2f, 2f, 0f, false, true, 15f, 5f)
        verticalLineTo(10f)
        moveTo(6.5f, 7f); horizontalLineTo(11.5f)
    }
    val folder: PathBuilder.() -> Unit = {
        moveTo(11f, 13f)
        horizontalLineTo(14f)
        lineTo(15.5f, 14.5f)
        horizontalLineTo(21f)
        verticalLineTo(21f)
        horizontalLineTo(11f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = folder)
    line(pathBuilder = folder)
}

// ── Sync & Network: cloud with up/down arrows ───────────────────────────────────
private fun syncNetwork(selected: Boolean) = kkcIcon("SettingsSyncNetwork.${state(selected)}") {
    val cloud: PathBuilder.() -> Unit = {
        moveTo(7f, 19f)
        horizontalLineTo(17.5f)
        arcTo(4f, 4f, 0f, false, false, 18.1f, 11f)
        arcTo(6f, 6f, 0f, false, false, 6.4f, 10.2f)
        arcTo(4.5f, 4.5f, 0f, false, false, 7f, 19f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = cloud)
    line(pathBuilder = cloud)
    line(width = 1.5f) {
        moveTo(10f, 16.5f); verticalLineTo(12f)
        moveTo(8.5f, 13.5f); lineTo(10f, 12f); lineTo(11.5f, 13.5f)
        moveTo(14f, 12f); verticalLineTo(16.5f)
        moveTo(12.5f, 15f); lineTo(14f, 16.5f); lineTo(15.5f, 15f)
    }
}

// ── Performance & Power: gauge with a solid hub ─────────────────────────────────
private fun performance(selected: Boolean) = kkcIcon("SettingsPerformance.${state(selected)}") {
    if (selected) solid(DUOTONE) {
        moveTo(3.5f, 17f)
        arcTo(9f, 9f, 0f, true, true, 20.5f, 17f)
        close()
    }
    line {
        moveTo(3.5f, 17f)
        arcTo(9f, 9f, 0f, true, true, 20.5f, 17f)
        moveTo(12f, 15.5f); lineTo(16f, 10f)
    }
    line(width = 1.5f) {
        moveTo(6f, 12.5f); horizontalLineTo(7.2f)
        moveTo(12f, 6.5f); verticalLineTo(7.7f)
        moveTo(18f, 12.5f); horizontalLineTo(16.8f)
        moveTo(7.8f, 8.3f); lineTo(8.7f, 9.2f)
    }
    solid { circle(12f, 15.5f, 1.8f) }
}

// ── Admin: shield with a keyhole ────────────────────────────────────────────────
private fun admin(selected: Boolean) = kkcIcon("SettingsAdmin.${state(selected)}") {
    val shield: PathBuilder.() -> Unit = {
        moveTo(12f, 2.5f)
        lineTo(19.5f, 5.5f)
        verticalLineTo(11f)
        curveTo(19.5f, 16f, 16.3f, 19.6f, 12f, 21.5f)
        curveTo(7.7f, 19.6f, 4.5f, 16f, 4.5f, 11f)
        verticalLineTo(5.5f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = shield)
    line(pathBuilder = shield)
    solid { circle(12f, 10.5f, 2f) }
    line { moveTo(12f, 12f); verticalLineTo(15.5f) }
}

val SettingsLookFeelSelected: ImageVector by lazy { lookFeel(true) }
val SettingsLookFeelUnselected: ImageVector by lazy { lookFeel(false) }
val SettingsViewersSelected: ImageVector by lazy { viewers(true) }
val SettingsViewersUnselected: ImageVector by lazy { viewers(false) }
val SettingsMeSelected: ImageVector by lazy { me(true) }
val SettingsMeUnselected: ImageVector by lazy { me(false) }
val SettingsUpdatesSelected: ImageVector by lazy { updates(true) }
val SettingsUpdatesUnselected: ImageVector by lazy { updates(false) }
val SettingsTabletDataSelected: ImageVector by lazy { tabletData(true) }
val SettingsTabletDataUnselected: ImageVector by lazy { tabletData(false) }
val SettingsSyncNetworkSelected: ImageVector by lazy { syncNetwork(true) }
val SettingsSyncNetworkUnselected: ImageVector by lazy { syncNetwork(false) }
val SettingsPerformanceSelected: ImageVector by lazy { performance(true) }
val SettingsPerformanceUnselected: ImageVector by lazy { performance(false) }
val SettingsAdminSelected: ImageVector by lazy { admin(true) }
val SettingsAdminUnselected: ImageVector by lazy { admin(false) }
```

`app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSectionIcons.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import com.kkc.sheettracker.ui.components.icons.SettingsAdminSelected
import com.kkc.sheettracker.ui.components.icons.SettingsAdminUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsLookFeelSelected
import com.kkc.sheettracker.ui.components.icons.SettingsLookFeelUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsMeSelected
import com.kkc.sheettracker.ui.components.icons.SettingsMeUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsPerformanceSelected
import com.kkc.sheettracker.ui.components.icons.SettingsPerformanceUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsSyncNetworkSelected
import com.kkc.sheettracker.ui.components.icons.SettingsSyncNetworkUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsTabletDataSelected
import com.kkc.sheettracker.ui.components.icons.SettingsTabletDataUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsUpdatesSelected
import com.kkc.sheettracker.ui.components.icons.SettingsUpdatesUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsViewersSelected
import com.kkc.sheettracker.ui.components.icons.SettingsViewersUnselected

internal fun SettingsSection.icon(selected: Boolean): ImageVector = when (this) {
    SettingsSection.LOOK_AND_FEEL -> if (selected) SettingsLookFeelSelected else SettingsLookFeelUnselected
    SettingsSection.VIEWERS -> if (selected) SettingsViewersSelected else SettingsViewersUnselected
    SettingsSection.ME -> if (selected) SettingsMeSelected else SettingsMeUnselected
    SettingsSection.UPDATES_ABOUT -> if (selected) SettingsUpdatesSelected else SettingsUpdatesUnselected
    SettingsSection.TABLET_DATA -> if (selected) SettingsTabletDataSelected else SettingsTabletDataUnselected
    SettingsSection.SYNC_NETWORK -> if (selected) SettingsSyncNetworkSelected else SettingsSyncNetworkUnselected
    SettingsSection.PERFORMANCE_POWER -> if (selected) SettingsPerformanceSelected else SettingsPerformanceUnselected
    SettingsSection.ADMIN -> if (selected) SettingsAdminSelected else SettingsAdminUnselected
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.SettingsIconsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/components/icons/SettingsIcons.kt app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsSectionIcons.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsIconsTest.kt
git commit -m "feat(settings): add settings section icon set"
```

---

### Task 3: Shared settings chrome + mode logo swap point

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/WorkModeArt.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsChrome.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsChromeWiringTest.kt`

**Interfaces:**
- Consumes: Task 1 (`SettingsSection`, `ChipTone`, `modeTileHeight`, `saveButtonState`), Task 2 (`SettingsSection.icon`), `Modifier.kkcCardDepth` (`com.kkc.sheettracker.ui.components`), `LocalKKCStatusColors` (`com.kkc.sheettracker.ui.theme`), `StationCncIcon`, `HardwoodsPlankIcon`, `ReferenceAssemblyIcon`, `HardwoodsSpecialtyIcon` (`com.kkc.sheettracker.ui.components.icons`).
- Produces (all `internal`, package `com.kkc.sheettracker.ui.settings`):
  - `class ModeLogo(val painter: Painter, val tintable: Boolean)`; `@Composable fun workModeLogo(mode: WorkMode): ModeLogo`; `fun WorkMode.displayName(): String`
  - `@Composable fun settingsFieldColors(): TextFieldColors`
  - `@Composable fun syncDotColor(tone: ChipTone): Color`
  - `@Composable fun StatusChip(label: String, onClick: () -> Unit, container: Color = surfaceVariant, content: Color = onSurfaceVariant, dot: Color? = null, icon: ImageVector? = null)`
  - `@Composable fun WorkModeRow(workMode: WorkMode, onWorkModeChanged: (WorkMode) -> Unit, flexibleModeEnabled: Boolean, onFlexibleModeChanged: (Boolean) -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun SettingsRail(selected: SettingsSection, onSelect: (SettingsSection) -> Unit, updatesBadge: Int, bottomClearance: Dp, modifier: Modifier = Modifier)`
  - `@Composable fun SectionHeader(section: SettingsSection)`
  - `@Composable fun GroupCaption(text: String, modifier: Modifier = Modifier)`
  - `@Composable fun GroupCard(caption: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun GroupDivider()`
  - `@Composable fun CardBody(content: @Composable ColumnScope.() -> Unit)` — 16dp padded column, 12dp spacing
  - `@Composable fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null)`
  - `@Composable fun SettingNavRow(label: String, subtitle: String, onClick: () -> Unit)`
  - `@Composable fun SaveRow(visible: Boolean, enabled: Boolean, saveLabel: String, savedFlash: Boolean, onClick: () -> Unit)`
  - `@Composable fun rememberSavedFlash(): MutableState<Boolean>` — auto-resets to false 1600ms after set true
  - `@Composable fun SaveableField(label: String, savedValue: String, onSave: (String) -> Unit, modifier: Modifier = Modifier, supportingText: String? = null, placeholder: String? = null, saveLabel: String = "Save", allowBlank: Boolean = false, password: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text)`

- [ ] **Step 1: Write the failing source-wiring test**

`app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsChromeWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChromeWiringTest {

    private val chrome = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsChrome.kt").readText()

    @Test
    fun saveableFieldReseedsFromSavedValueAndSurvivesRotation() {
        // Keyed on savedValue: an IP flow emitting after first frame replaces the text.
        // rememberSaveable: unsaved edits survive rotation.
        assertTrue(chrome.contains("rememberSaveable(savedValue)"))
    }

    @Test
    fun modeTilesUseTheSharedHeightRule() {
        assertTrue(chrome.contains("modeTileHeight("))
    }

    @Test
    fun noShadowBleedPatterns() {
        assertFalse("CLAUDE.md: never Surface(shadowElevation)", chrome.contains("shadowElevation"))
        assertTrue("cards use kkcCardDepth", chrome.contains("kkcCardDepth("))
    }

    @Test
    fun workModeArtIsTheSingleLogoSwapPoint() {
        val art = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/WorkModeArt.kt").readText()
        assertTrue(art.contains("fun workModeLogo(mode: WorkMode): ModeLogo"))
        assertTrue(chrome.contains("workModeLogo("))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.SettingsChromeWiringTest"`
Expected: FAIL — `IllegalStateException: Unable to locate com/kkc/sheettracker/ui/settings/SettingsChrome.kt`.

- [ ] **Step 3: Implement `WorkModeArt.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.ui.components.icons.HardwoodsPlankIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsSpecialtyIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceAssemblyIcon
import com.kkc.sheettracker.ui.components.icons.StationCncIcon

/** [tintable] = single-color vector the tile recolors; false for full-color artwork. */
internal class ModeLogo(val painter: Painter, val tintable: Boolean)

/**
 * The one place mode tile artwork comes from. Today: KKC station icons.
 * For the comic logos: add res/drawable-nodpi/mode_logo_{cnc,hardwoods,assembly,specialty}.png
 * (square, >= 512px, transparent) and return ModeLogo(painterResource(R.drawable.mode_logo_x), tintable = false).
 */
@Composable
internal fun workModeLogo(mode: WorkMode): ModeLogo {
    val vector = when (mode) {
        WorkMode.CNC -> StationCncIcon
        WorkMode.HARDWOODS -> HardwoodsPlankIcon
        WorkMode.ASSEMBLY -> ReferenceAssemblyIcon
        WorkMode.SPECIALTY -> HardwoodsSpecialtyIcon
    }
    return ModeLogo(rememberVectorPainter(vector), tintable = true)
}

internal fun WorkMode.displayName(): String = when (this) {
    WorkMode.CNC -> "CNC"
    WorkMode.HARDWOODS -> "Hardwoods"
    WorkMode.ASSEMBLY -> "Assembly"
    WorkMode.SPECIALTY -> "Specialty"
}
```

- [ ] **Step 4: Implement `SettingsChrome.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.ui.components.kkcCardDepth
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(12.dp)
private val RailItemShape = RoundedCornerShape(9.dp)
private const val SAVED_FLASH_MS = 1600L

@Composable
internal fun settingsFieldColors(): TextFieldColors {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    return OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = containerColor,
        unfocusedBorderColor = Color.Transparent,
        focusedContainerColor = containerColor,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
    )
}

// ── Status chips ────────────────────────────────────────────────────────────────

@Composable
internal fun syncDotColor(tone: ChipTone): Color {
    val status = LocalKKCStatusColors.current
    return when (tone) {
        ChipTone.OK -> status.complete
        ChipTone.WARN -> status.skip
        ChipTone.BAD -> status.bad
    }
}

@Composable
internal fun StatusChip(
    label: String,
    onClick: () -> Unit,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dot: Color? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1
        )
    }
}

// ── Work mode row ───────────────────────────────────────────────────────────────

@Composable
internal fun WorkModeRow(
    workMode: WorkMode,
    onWorkModeChanged: (WorkMode) -> Unit,
    flexibleModeEnabled: Boolean,
    onFlexibleModeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GroupCaption("Work mode", Modifier.weight(1f))
            Text(
                "Flexible mode",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Switch(checked = flexibleModeEnabled, onCheckedChange = onFlexibleModeChanged)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val tileWidth = (maxWidth - gap * 3) / 4
            val tileHeight = modeTileHeight(tileWidth)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                WorkMode.entries.forEach { mode ->
                    ModeTile(
                        mode = mode,
                        selected = mode == workMode,
                        onClick = { onWorkModeChanged(mode) },
                        modifier = Modifier.width(tileWidth).height(tileHeight)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTile(mode: WorkMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primary else scheme.surface
    val content = if (selected) scheme.onPrimary else scheme.onSurface
    val logo = workModeLogo(mode)
    Column(
        modifier = modifier
            .kkcCardDepth(CardShape, elevation = if (selected) 0.dp else 2.dp)
            .background(container)
            .then(if (selected) Modifier else Modifier.border(1.dp, scheme.outlineVariant, CardShape))
            .clickable(onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = logo.painter,
            contentDescription = null,
            colorFilter = if (logo.tintable) ColorFilter.tint(if (selected) content else scheme.primary) else null,
            modifier = Modifier.fillMaxHeight(0.52f).aspectRatio(1f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            mode.displayName().uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            color = content,
            maxLines = 1
        )
    }
}

// ── Rail ────────────────────────────────────────────────────────────────────────

@Composable
internal fun SettingsRail(
    selected: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
    updatesBadge: Int,
    bottomClearance: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(240.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomClearance)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .kkcCardDepth(CardShape, elevation = 2.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SettingsSection.entries.forEachIndexed { index, section ->
                val firstAdvanced = section.isAdvanced && index > 0 && !SettingsSection.entries[index - 1].isAdvanced
                if (firstAdvanced) {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    GroupCaption("Advanced", Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
                }
                RailItem(
                    section = section,
                    selected = section == selected,
                    badge = if (section == SettingsSection.UPDATES_ABOUT) updatesBadge else 0,
                    onClick = { onSelect(section) }
                )
            }
        }
    }
}

@Composable
private fun RailItem(section: SettingsSection, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val content = when {
        selected -> scheme.onSecondaryContainer
        section.isAdvanced -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RailItemShape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(section.icon(selected), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        Text(
            section.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (badge > 0) {
            Box(
                modifier = Modifier.size(20.dp).clip(CircleShape).background(LocalKKCStatusColors.current.skipBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$badge",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
            }
        }
    }
}

// ── Pane building blocks ────────────────────────────────────────────────────────

@Composable
internal fun SectionHeader(section: SettingsSection) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier.size(48.dp).clip(CardShape).background(scheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                section.icon(selected = true),
                contentDescription = null,
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }
        Column {
            Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(section.subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GroupCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun GroupCard(
    caption: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (caption != null) GroupCaption(caption, Modifier.padding(start = 4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .kkcCardDepth(CardShape, elevation = 2.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun CardBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun SettingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun SettingNavRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Save pattern ────────────────────────────────────────────────────────────────

@Composable
internal fun rememberSavedFlash(): MutableState<Boolean> {
    val flash = remember { mutableStateOf(false) }
    LaunchedEffect(flash.value) {
        if (flash.value) {
            delay(SAVED_FLASH_MS)
            flash.value = false
        }
    }
    return flash
}

@Composable
internal fun SaveRow(visible: Boolean, enabled: Boolean, saveLabel: String, savedFlash: Boolean, onClick: () -> Unit) {
    if (!visible && !savedFlash) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (visible) {
            Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp)) { Text(saveLabel) }
        }
        if (savedFlash) {
            Text("Saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun SaveableField(
    label: String,
    savedValue: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    placeholder: String? = null,
    saveLabel: String = "Save",
    allowBlank: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by rememberSaveable(savedValue) { mutableStateOf(savedValue) }
    var savedFlash by rememberSavedFlash()
    val button = saveButtonState(text, savedValue, allowBlank)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            colors = settingsFieldColors(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType)
        )
        SaveRow(
            visible = button.visible,
            enabled = button.enabled,
            saveLabel = saveLabel,
            savedFlash = savedFlash,
            onClick = {
                onSave(text.trim())
                savedFlash = true
            }
        )
    }
}
```

- [ ] **Step 5: Run test + compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.SettingsChromeWiringTest"`
Expected: PASS (4 tests). This also compiles all main sources; a compile error here fails the run — fix and rerun.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/settings/WorkModeArt.kt app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsChrome.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsChromeWiringTest.kt
git commit -m "feat(settings): add shared settings chrome and mode logo swap point"
```

---

### Task 4: Look & Feel pane (theme swatches)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/LookAndFeelPane.kt`

**Interfaces:**
- Consumes: Task 1 (`selectedThemeId`, `nflCardLabel`, `themeSwatch`), Task 3 (`GroupCard`, `GroupDivider`, `SettingToggle`, `settingsFieldColors`), `ThemePickerLogic.kt` (`customThemes`, `footballTeamThemes`, `filterThemesByQuery`), `KKCThemeCatalog`.
- Produces: `@Composable internal fun LookAndFeelPane(isDarkTheme: Boolean, followSystemTheme: Boolean, darkThemeOverride: Boolean, onFollowSystemThemeChanged: (Boolean) -> Unit, onThemeChanged: (Boolean) -> Unit, themeCatalog: KKCThemeCatalog, onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit, onThemeOverrideChanged: (String?) -> Unit, onThemeCatalogReload: () -> Unit)` in package `com.kkc.sheettracker.ui.settings.panes`.

Pure logic for this pane is already pinned by Task 1 tests (`selectedThemeId`, `nflCardLabel`, `themeSwatch`); this task's gate is compilation plus Task 7's wiring test.

- [ ] **Step 1: Implement**

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.ui.settings.SettingToggle
import com.kkc.sheettracker.ui.settings.customThemes
import com.kkc.sheettracker.ui.settings.filterThemesByQuery
import com.kkc.sheettracker.ui.settings.footballTeamThemes
import com.kkc.sheettracker.ui.settings.nflCardLabel
import com.kkc.sheettracker.ui.settings.selectedThemeId
import com.kkc.sheettracker.ui.settings.settingsFieldColors
import com.kkc.sheettracker.ui.settings.themeSwatch
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookAndFeelPane(
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pick: (String) -> Unit = { id ->
        onThemeFollowSyncedDefaultChanged(false)
        onThemeOverrideChanged(id)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Brightness") {
            SettingToggle(
                label = "Follow system theme",
                checked = followSystemTheme,
                onCheckedChange = onFollowSystemThemeChanged,
                subtitle = "Match the tablet's light/dark setting"
            )
            if (!followSystemTheme) {
                GroupDivider()
                SettingToggle(label = "Dark mode", checked = darkThemeOverride, onCheckedChange = onThemeChanged)
            }
        }

        GroupCard(caption = "Theme") {
            var nflSearch by rememberSaveable { mutableStateOf(false) }
            var query by rememberSaveable { mutableStateOf("") }
            val selectedId = selectedThemeId(themeCatalog)
            val custom = customThemes(themeCatalog.themes).ifEmpty { listOf(themeCatalog.activeTheme) }
            val nfl = footballTeamThemes(themeCatalog.themes)
            val activeNfl = nfl.firstOrNull { it.id == selectedId }

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                custom.forEach { theme ->
                    ThemeSwatchCard(
                        name = theme.name,
                        colors = themeSwatch(theme, isDarkTheme),
                        selected = theme.id == selectedId,
                        onClick = { pick(theme.id) }
                    )
                }
                if (nfl.isNotEmpty()) {
                    ThemeSwatchCard(
                        name = nflCardLabel(themeCatalog),
                        colors = activeNfl?.let { themeSwatch(it, isDarkTheme) } ?: (scheme.outline to scheme.outlineVariant),
                        selected = activeNfl != null,
                        onClick = { nflSearch = true }
                    )
                }
            }

            if (nflSearch) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search NFL team") },
                        supportingText = { Text("Applies only to this tablet") },
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    val matches = filterThemesByQuery(nfl, query)
                    if (matches.isEmpty()) {
                        Text("No matching teams", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    matches.forEach { team ->
                        val (primary, secondary) = themeSwatch(team, isDarkTheme)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    pick(team.id)
                                    nflSearch = false
                                    query = ""
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(primary))
                            Box(Modifier.size(14.dp).clip(CircleShape).background(secondary))
                            Text(team.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    TextButton(onClick = { nflSearch = false; query = "" }) { Text("Cancel") }
                }
            }

            GroupDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (themeCatalog.overrideThemeId == null) "Using fleet default" else "Applies only to this tablet",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onThemeCatalogReload, shape = RoundedCornerShape(8.dp)) {
                    Text("Reload themes")
                }
                Button(
                    onClick = {
                        onThemeOverrideChanged(null)
                        onThemeFollowSyncedDefaultChanged(true)
                    },
                    enabled = themeCatalog.overrideThemeId != null,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Use fleet default")
                }
            }
        }

        val problems = themeCatalog.loadMessages +
            themeCatalog.invalidThemes.map { "${it.filename}: ${it.message}" }
        problems.forEach { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = scheme.error)
        }
    }
}

@Composable
private fun ThemeSwatchCard(name: String, colors: Pair<Color, Color>, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(shape)
            .border(2.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp))) {
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.first))
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.second))
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/settings/panes/LookAndFeelPane.kt
git commit -m "feat(settings): add Look & Feel pane with theme swatches"
```

---

### Task 5: Viewers, Me, and Updates & About panes

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/ViewersPane.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/MePane.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/UpdatesAboutPane.kt`

**Interfaces:**
- Consumes: Task 1 (`saveButtonState`), Task 3 (`GroupCard`, `GroupDivider`, `CardBody`, `SettingToggle`, `SettingNavRow`, `SaveRow`, `rememberSavedFlash`, `settingsFieldColors`), `UiPreferencesStore` (`getScrollPreviewLabelOnly()`, `setScrollPreviewLabelOnly(Boolean)`), `EmployeeDirectory.recordsFlow` (records with `pin`, `name`, `displayName`), `ExternalAppUpdate` (`appName`, `versionName`, `isInstalled`), `BuildConfig.VERSION_NAME`.
- Produces (package `com.kkc.sheettracker.ui.settings.panes`):
  - `internal fun ViewersPane(isDarkTheme: Boolean, useStandardSheets: Boolean, onUseStandardSheetsChanged: (Boolean) -> Unit, continuousScrollDefault: Boolean, onContinuousScrollDefaultChanged: (Boolean) -> Unit, uiPreferencesStore: UiPreferencesStore, onOpenAssemblyViewerDefaults: () -> Unit, onOpenSpecialtyViewerDefaults: () -> Unit)`
  - `internal fun MePane(employeeName: String, onEmployeeNameChanged: (String) -> Unit)`
  - `internal fun UpdatesAboutPane(pendingSelfUpdate: File?, pendingExternalUpdates: List<ExternalAppUpdate>, onInstallSelfUpdate: () -> Unit, onInstallExternalUpdate: (ExternalAppUpdate) -> Unit, onInstallAll: () -> Unit, onCheckForUpdates: () -> Unit, isDebugBuild: Boolean, onReinstallLatest: () -> Unit)`

- [ ] **Step 1: Implement `ViewersPane.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.ui.settings.SettingNavRow
import com.kkc.sheettracker.ui.settings.SettingToggle

@Composable
internal fun ViewersPane(
    isDarkTheme: Boolean,
    useStandardSheets: Boolean,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    continuousScrollDefault: Boolean,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    uiPreferencesStore: UiPreferencesStore,
    onOpenAssemblyViewerDefaults: () -> Unit,
    onOpenSpecialtyViewerDefaults: () -> Unit,
) {
    var scrollPreviewLabelOnly by remember { mutableStateOf(uiPreferencesStore.getScrollPreviewLabelOnly()) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Sheets") {
            if (isDarkTheme) {
                SettingToggle(
                    label = "Use standard sheets",
                    checked = useStandardSheets,
                    onCheckedChange = onUseStandardSheetsChanged,
                    subtitle = "Load light mode PDFs instead of dark mode in viewer pages."
                )
                GroupDivider()
            }
            SettingToggle(
                label = "Continuous scroll",
                checked = continuousScrollDefault,
                onCheckedChange = onContinuousScrollDefaultChanged,
                subtitle = "Scroll reference PDFs page-to-page instead of tapping through them."
            )
            GroupDivider()
            SettingToggle(
                label = "Label-only scroll preview",
                checked = scrollPreviewLabelOnly,
                onCheckedChange = {
                    scrollPreviewLabelOnly = it
                    uiPreferencesStore.setScrollPreviewLabelOnly(it)
                },
                subtitle = "Show just the sheet label while dragging the scrollbar, instead of page thumbnails."
            )
        }
        GroupCard(caption = "Viewer defaults") {
            SettingNavRow("Assembly viewer defaults", "Layout, panes, fullscreen", onOpenAssemblyViewerDefaults)
            GroupDivider()
            SettingNavRow("Specialty viewer defaults", "Station order, expanded sections", onOpenSpecialtyViewerDefaults)
        }
    }
}
```

- [ ] **Step 2: Implement `MePane.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.EmployeeDirectory
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.SaveRow
import com.kkc.sheettracker.ui.settings.rememberSavedFlash
import com.kkc.sheettracker.ui.settings.saveButtonState
import com.kkc.sheettracker.ui.settings.settingsFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MePane(employeeName: String, onEmployeeNameChanged: (String) -> Unit) {
    val records by EmployeeDirectory.recordsFlow.collectAsState()
    var text by rememberSaveable(employeeName) { mutableStateOf(employeeName) }
    var expanded by remember { mutableStateOf(false) }
    var savedFlash by rememberSavedFlash()
    val matches = remember(text, records) {
        if (text.isBlank()) emptyList()
        else records.filter {
            it.name.contains(text, ignoreCase = true) ||
                it.pin.contains(text, ignoreCase = true) ||
                it.displayName.contains(text, ignoreCase = true)
        }
    }
    val button = saveButtonState(text, employeeName, allowBlank = true)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Hours Tracker login") {
            CardBody {
                ExposedDropdownMenuBox(
                    expanded = expanded && matches.isNotEmpty(),
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            expanded = it.isNotBlank()
                        },
                        label = { Text("Your Name / PIN") },
                        supportingText = { Text("Used for auto-login to the Hours Tracker. Leave blank to be prompted each time.") },
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (matches.isNotEmpty()) {
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            matches.forEach { record ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (record.displayName.isNotBlank()) "${record.displayName} (${record.pin})"
                                            else record.name
                                        )
                                    },
                                    onClick = {
                                        text = record.name
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                SaveRow(
                    visible = button.visible,
                    enabled = button.enabled,
                    saveLabel = "Save name",
                    savedFlash = savedFlash,
                    onClick = {
                        onEmployeeNameChanged(text.trim())
                        savedFlash = true
                    }
                )
            }
        }
    }
}
```

Note: `ExposedDropdownMenu` is a member of `ExposedDropdownMenuBoxScope`; no import needed.

- [ ] **Step 3: Implement `UpdatesAboutPane.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.update.ExternalAppUpdate
import java.io.File

@Composable
internal fun UpdatesAboutPane(
    pendingSelfUpdate: File?,
    pendingExternalUpdates: List<ExternalAppUpdate>,
    onInstallSelfUpdate: () -> Unit,
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit,
    onInstallAll: () -> Unit,
    onCheckForUpdates: () -> Unit,
    isDebugBuild: Boolean,
    onReinstallLatest: () -> Unit,
) {
    val hasSelfUpdate = pendingSelfUpdate != null
    val hasExternalUpdates = pendingExternalUpdates.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (hasSelfUpdate || hasExternalUpdates) {
            GroupCard(caption = "Pending updates") {
                if (hasSelfUpdate && hasExternalUpdates) {
                    CardBody {
                        Button(onClick = onInstallAll, modifier = Modifier.fillMaxWidth()) { Text("Update All") }
                    }
                    GroupDivider()
                }
                if (hasSelfUpdate) {
                    UpdateRow("KKC Sheet Tracker update available", "Update", onInstallSelfUpdate)
                }
                pendingExternalUpdates.forEachIndexed { index, update ->
                    if (hasSelfUpdate || index > 0) GroupDivider()
                    UpdateRow(
                        "${update.appName} ${update.versionName} available",
                        if (update.isInstalled) "Update" else "Install"
                    ) { onInstallExternalUpdate(update) }
                }
            }
        }

        GroupCard(caption = "About") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "KKC Sheet Tracker v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onCheckForUpdates, shape = RoundedCornerShape(8.dp)) {
                    Text("Check for updates")
                }
            }
            if (isDebugBuild) {
                GroupDivider()
                TextButton(onClick = onReinstallLatest, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Reinstall Latest Debug APK")
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(label: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Button(onClick = onClick, shape = RoundedCornerShape(8.dp)) { Text(action) }
    }
}
```

- [ ] **Step 4: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `EmployeeDirectory` record properties differ from `pin`/`name`/`displayName`, check `data/EmployeeDirectory.kt` — the old `SettingsScreen.kt` mapped `Triple(it.pin, it.name, it.displayName)`, so those names are correct.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/settings/panes/ViewersPane.kt app/src/main/java/com/kkc/sheettracker/ui/settings/panes/MePane.kt app/src/main/java/com/kkc/sheettracker/ui/settings/panes/UpdatesAboutPane.kt
git commit -m "feat(settings): add Viewers, Me, and Updates & About panes"
```

---

### Task 6: Advanced panes (Tablet & Data, Sync & Network, Performance & Power, Admin)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/TabletDataPane.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/SyncNetworkPane.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/PerformancePowerPane.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/AdminPane.kt`

**Interfaces:**
- Consumes: Task 1 (`storedIpOrNull`), Task 3 (`GroupCard`, `GroupDivider`, `CardBody`, `SettingToggle`, `SaveableField`, `settingsFieldColors`), `TimecardServerConfig` / `AdminSyncConfig` (`serverIpFlow: Flow<String?>`, `suspend fun setManualIp(String?)`), `SyncthingStatusUiState`, `SyncthingServiceStatus`, `UiPreferencesStore` (low-end getters/setters), `IdlePowerSaveStore` (`configFlow`, `suspend setEnabled/setIdleTimeoutSeconds/setSyncthingPauseTimeoutSeconds`), `IdlePowerSaveConfig`, `AdminModeController.setEnabled(Boolean)`.
- Produces (package `com.kkc.sheettracker.ui.settings.panes`):
  - `internal fun TabletDataPane(tabletId: String, onTabletIdChanged: (String) -> Unit, basePath: String, onBasePathChanged: (String) -> Unit)`
  - `internal fun SyncNetworkPane(syncthingApiKey: String, syncthingStatus: SyncthingStatusUiState, onSyncthingApiKeySave: (String) -> Unit, onSyncthingCheckNow: () -> Unit, onSyncthingStartNow: () -> Unit, timecardConfig: TimecardServerConfig, adminSyncConfig: AdminSyncConfig)`
  - `internal fun PerformancePowerPane(uiPreferencesStore: UiPreferencesStore, idlePowerSaveStore: IdlePowerSaveStore)`
  - `internal fun AdminPane(adminMode: Boolean, onUnlockRequested: () -> Unit)`

- [ ] **Step 1: Implement `TabletDataPane.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.SaveableField

@Composable
internal fun TabletDataPane(
    tabletId: String,
    onTabletIdChanged: (String) -> Unit,
    basePath: String,
    onBasePathChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Tablet") {
            CardBody {
                SaveableField(
                    label = "Tablet ID",
                    savedValue = tabletId,
                    onSave = onTabletIdChanged,
                    supportingText = "Used for progress file naming. Must be unique per tablet.",
                    saveLabel = "Save Tablet ID"
                )
            }
        }
        GroupCard(caption = "Data source") {
            CardBody {
                SaveableField(
                    label = "Ready Jobs Folder Path",
                    savedValue = basePath,
                    onSave = onBasePathChanged,
                    supportingText = "Path to the synced Ready Jobs folder on this tablet.",
                    saveLabel = "Save Path (app will restart)"
                )
            }
        }
    }
}
```

- [ ] **Step 2: Implement `SyncNetworkPane.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.SaveableField
import com.kkc.sheettracker.ui.settings.storedIpOrNull
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SyncNetworkPane(
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    timecardConfig: TimecardServerConfig,
    adminSyncConfig: AdminSyncConfig,
) {
    val scope = rememberCoroutineScope()
    val currentServerIp by timecardConfig.serverIpFlow.collectAsState(initial = null)
    val currentAdminSyncIp by adminSyncConfig.serverIpFlow.collectAsState(initial = null)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Syncthing") {
            CardBody {
                SyncStatusBadge(syncthingStatus.status)
                syncthingStatus.lastCheckedAtMs?.let {
                    Text("Last check: ${clockTime(it)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                syncthingStatus.lastStartAttemptAtMs?.let {
                    Text("Last restart attempt: ${clockTime(it)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                SaveableField(
                    label = "Syncthing API Key",
                    savedValue = syncthingApiKey,
                    onSave = onSyncthingApiKeySave,
                    supportingText = "Used for localhost API checks at 127.0.0.1:8384.",
                    saveLabel = "Save API Key",
                    password = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSyncthingCheckNow,
                        enabled = syncthingApiKey.isNotBlank(),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Check Now") }
                    Button(
                        onClick = onSyncthingStartNow,
                        enabled = syncthingApiKey.isNotBlank(),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Start Now") }
                }
            }
        }
        GroupCard(caption = "Timeclock hub") {
            CardBody {
                SaveableField(
                    label = "Server IP address",
                    savedValue = currentServerIp ?: "",
                    onSave = { text -> scope.launch { timecardConfig.setManualIp(storedIpOrNull(text)) } },
                    placeholder = "Auto (mDNS discovery)",
                    supportingText = "Leave blank to use automatic discovery. Enter an IP to skip mDNS.",
                    allowBlank = true,
                    keyboardType = KeyboardType.Uri
                )
            }
        }
        GroupCard(caption = "Hours Tracker admin sync") {
            CardBody {
                SaveableField(
                    label = "Hours Tracker server IP address",
                    savedValue = currentAdminSyncIp ?: "",
                    onSave = { text -> scope.launch { adminSyncConfig.setManualIp(storedIpOrNull(text)) } },
                    placeholder = "Not configured (fast path disabled)",
                    supportingText = "Enables instant job order / job board / delivery schedule sync. Leave blank to always use the existing (slower) sync mechanism.",
                    allowBlank = true,
                    keyboardType = KeyboardType.Uri
                )
            }
        }
    }
}

@Composable
private fun SyncStatusBadge(status: SyncthingServiceStatus) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, text) = when (status) {
        SyncthingServiceStatus.CHECKING -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, "Checking")
        SyncthingServiceStatus.RUNNING -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, "Running")
        SyncthingServiceStatus.PAUSED -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, "Paused")
        SyncthingServiceStatus.NOT_RUNNING -> Triple(scheme.errorContainer, scheme.onErrorContainer, "Not running")
        SyncthingServiceStatus.START_FAILED -> Triple(scheme.errorContainer, scheme.onErrorContainer, "Start failed")
        SyncthingServiceStatus.API_KEY_REQUIRED -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, "API key required")
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text("Status: $text", style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

private fun clockTime(timestampMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))
```

- [ ] **Step 3: Implement `PerformancePowerPane.kt`** (logic moved verbatim from old `SettingsScreen.kt` lines 142-159 and 593-706)

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.IdlePowerSaveConfig
import com.kkc.sheettracker.data.IdlePowerSaveStore
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.ui.settings.SettingToggle
import com.kkc.sheettracker.ui.settings.settingsFieldColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PerformancePowerPane(
    uiPreferencesStore: UiPreferencesStore,
    idlePowerSaveStore: IdlePowerSaveStore,
) {
    var lowEndMode by remember { mutableStateOf(uiPreferencesStore.getLowEndMode()) }
    var animationsEnabled by remember { mutableStateOf(uiPreferencesStore.getAnimationsEnabled()) }
    var shadowsEnabled by remember { mutableStateOf(uiPreferencesStore.getShadowsEnabled()) }
    var blurEnabled by remember { mutableStateOf(uiPreferencesStore.getBlurEnabled()) }
    var lazyLoadingEnabled by remember { mutableStateOf(uiPreferencesStore.getLazyLoadingEnabled()) }

    val idleConfig by idlePowerSaveStore.configFlow.collectAsState(initial = IdlePowerSaveConfig())
    val scope = rememberCoroutineScope()
    var idleTimeoutText by remember(idleConfig.idleTimeoutSeconds) {
        mutableStateOf(idleConfig.idleTimeoutSeconds.toString())
    }
    var syncthingPauseText by remember(idleConfig.syncthingPauseTimeoutSeconds) {
        mutableStateOf(idleConfig.syncthingPauseTimeoutSeconds.toString())
    }
    LaunchedEffect(idleTimeoutText) {
        val seconds = idleTimeoutText.toIntOrNull() ?: return@LaunchedEffect
        delay(500L)
        idlePowerSaveStore.setIdleTimeoutSeconds(seconds)
    }
    LaunchedEffect(syncthingPauseText) {
        val seconds = syncthingPauseText.toIntOrNull() ?: return@LaunchedEffect
        delay(500L)
        idlePowerSaveStore.setSyncthingPauseTimeoutSeconds(seconds)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Performance") {
            SettingToggle(
                label = "Low-end device mode",
                checked = lowEndMode,
                onCheckedChange = { enabled ->
                    lowEndMode = enabled
                    uiPreferencesStore.setLowEndMode(enabled)
                    if (enabled) {
                        animationsEnabled = false
                        shadowsEnabled = false
                        blurEnabled = false
                        lazyLoadingEnabled = true
                        uiPreferencesStore.setAnimationsEnabled(false)
                        uiPreferencesStore.setShadowsEnabled(false)
                        uiPreferencesStore.setBlurEnabled(false)
                        uiPreferencesStore.setLazyLoadingEnabled(true)
                    }
                }
            )
            if (lowEndMode) {
                Column(Modifier.padding(start = 16.dp)) {
                    GroupDivider()
                    SettingToggle("Animations", animationsEnabled, {
                        animationsEnabled = it
                        uiPreferencesStore.setAnimationsEnabled(it)
                    }, "Spring/tween transitions, animated content size")
                    SettingToggle("Shadows", shadowsEnabled, {
                        shadowsEnabled = it
                        uiPreferencesStore.setShadowsEnabled(it)
                    }, "Card/button elevation shadows")
                    SettingToggle("Frosted glass / blur", blurEnabled, {
                        blurEnabled = it
                        uiPreferencesStore.setBlurEnabled(it)
                    }, "hazeEffect() backgrounds, blur modifiers")
                    SettingToggle("Lazy data loading", lazyLoadingEnabled, {
                        lazyLoadingEnabled = it
                        uiPreferencesStore.setLazyLoadingEnabled(it)
                    }, "Paginate job/supply lists, defer heavy loads")
                }
            }
        }

        GroupCard(caption = "Idle power saving") {
            SettingToggle(
                label = "Enable idle power saving",
                checked = idleConfig.enabled,
                onCheckedChange = { enabled -> scope.launch { idlePowerSaveStore.setEnabled(enabled) } },
                subtitle = "Switches to dark sheets + black background to save battery on tablets left on but idle. Reverts instantly on touch."
            )
            if (idleConfig.enabled) {
                GroupDivider()
                CardBody {
                    OutlinedTextField(
                        value = idleTimeoutText,
                        onValueChange = { idleTimeoutText = it },
                        label = { Text("Dim after (seconds)") },
                        supportingText = { Text("Lower values (e.g. 5) are useful for testing. Default 300 (5 min).") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = syncthingPauseText,
                        onValueChange = { syncthingPauseText = it },
                        label = { Text("Pause Syncthing after (seconds)") },
                        supportingText = { Text("Default 1800 (30 min).") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Implement `AdminPane.kt`**

```kotlin
package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard

@Composable
internal fun AdminPane(adminMode: Boolean, onUnlockRequested: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Admin mode") {
            if (!adminMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Admin", style = MaterialTheme.typography.bodyLarge)
                        Text("Unlock advanced controls", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    OutlinedButton(onClick = onUnlockRequested, shape = RoundedCornerShape(8.dp)) { Text("Unlock") }
                }
            } else {
                CardBody {
                    Text("Admin mode is ON", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        "The supply \"To Order\" tab is visible, and the Jobs tab shows a reorder control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                    TextButton(onClick = { AdminModeController.setEnabled(false) }) { Text("Lock admin") }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/settings/panes/TabletDataPane.kt app/src/main/java/com/kkc/sheettracker/ui/settings/panes/SyncNetworkPane.kt app/src/main/java/com/kkc/sheettracker/ui/settings/panes/PerformancePowerPane.kt app/src/main/java/com/kkc/sheettracker/ui/settings/panes/AdminPane.kt
git commit -m "feat(settings): add advanced settings panes"
```

---

### Task 7: Rewrite the SettingsScreen shell

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` (replace lines 1-47 imports and 96-1275 body/helpers; keep lines 48-95 signature verbatim)
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenPendingUpdatesWiringTest.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsPanesWiringTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1-6.
- Produces: `SettingsScreen(...)` with unchanged public signature.

- [ ] **Step 1: Write the failing wiring tests**

`app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsPanesWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPanesWiringTest {

    private val screen = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsScreen.kt").readText()

    @Test
    fun everySectionDispatchesToItsPane() {
        val panes = mapOf(
            SettingsSection.LOOK_AND_FEEL to "LookAndFeelPane(",
            SettingsSection.VIEWERS to "ViewersPane(",
            SettingsSection.ME to "MePane(",
            SettingsSection.UPDATES_ABOUT to "UpdatesAboutPane(",
            SettingsSection.TABLET_DATA to "TabletDataPane(",
            SettingsSection.SYNC_NETWORK to "SyncNetworkPane(",
            SettingsSection.PERFORMANCE_POWER to "PerformancePowerPane(",
            SettingsSection.ADMIN to "AdminPane(",
        )
        assertTrue("every section mapped", panes.keys == SettingsSection.entries.toSet())
        panes.forEach { (section, call) ->
            assertTrue("missing branch for $section", screen.contains("SettingsSection.${section.name} -> $call"))
        }
    }

    @Test
    fun publicSignatureIsUnchanged() {
        val params = listOf(
            "tabletId: String,",
            "basePath: String,",
            "isDebugBuild: Boolean,",
            "isDarkTheme: Boolean,",
            "followSystemTheme: Boolean = true,",
            "darkThemeOverride: Boolean = false,",
            "workMode: WorkMode,",
            "flexibleModeEnabled: Boolean,",
            "onThemeChanged: (Boolean) -> Unit,",
            "onFollowSystemThemeChanged: (Boolean) -> Unit = {},",
            "onWorkModeChanged: (WorkMode) -> Unit,",
            "onFlexibleModeChanged: (Boolean) -> Unit,",
            "onReinstallLatest: () -> Unit,",
            "onCheckForUpdates: () -> Unit = {},",
            "onTabletIdChanged: (String) -> Unit,",
            "onBasePathChanged: (String) -> Unit,",
            "syncthingApiKey: String,",
            "syncthingStatus: SyncthingStatusUiState,",
            "onSyncthingApiKeySave: (String) -> Unit,",
            "onSyncthingCheckNow: () -> Unit,",
            "onSyncthingStartNow: () -> Unit,",
            "onBack: () -> Unit,",
            "employeeName: String,",
            "onEmployeeNameChanged: (String) -> Unit,",
            "useStandardSheets: Boolean = false,",
            "onUseStandardSheetsChanged: (Boolean) -> Unit = {},",
            "continuousScrollDefault: Boolean = false,",
            "onContinuousScrollDefaultChanged: (Boolean) -> Unit = {},",
            "timecardConfig: TimecardServerConfig,",
            "adminSyncConfig: AdminSyncConfig,",
            "themeCatalog: KKCThemeCatalog = KKCThemeRepository.builtInCatalog(),",
            "onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit = {},",
            "onThemeOverrideChanged: (String?) -> Unit = {},",
            "onThemeCatalogReload: () -> Unit = {},",
            "onOpenAssemblyViewerDefaults: () -> Unit = {},",
            "onOpenSpecialtyViewerDefaults: () -> Unit = {},",
            "uiPreferencesStore: UiPreferencesStore,",
            "idlePowerSaveStore: IdlePowerSaveStore,",
            "pendingSelfUpdate: File? = null,",
            "pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),",
            "onInstallSelfUpdate: () -> Unit = {},",
            "onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},",
            "onInstallAll: () -> Unit = {},",
        )
        params.forEach { assertTrue("SettingsScreen lost parameter `$it`", screen.contains(it)) }
    }

    @Test
    fun openEffectsAndSectionStateArePreserved() {
        assertTrue(screen.contains("EmployeeDirectory.refresh(File(basePath))"))
        assertTrue(screen.contains("onCheckForUpdates()"))
        assertTrue("section survives rotation", screen.contains("rememberSaveable"))
        assertTrue(screen.contains("initialSection("))
        assertTrue(screen.contains("AdminPasswordDialog("))
    }

    @Test
    fun oldSingleScrollHelpersAreGone() {
        listOf("SettingsCard(", "WorkModeIconTile(", "filledFieldColors(").forEach {
            assertFalse("$it should be removed", screen.contains(it))
        }
    }
}
```

Replace the body of `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenPendingUpdatesWiringTest.kt` with:

```kotlin
package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenPendingUpdatesWiringTest {

    @Test
    fun pendingUpdatesSectionIsWired() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsScreen.kt").readText()
        val pane = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/panes/UpdatesAboutPane.kt").readText()

        assertTrue("SettingsScreen must accept pendingSelfUpdate", source.contains("pendingSelfUpdate: File? = null"))
        assertTrue("SettingsScreen must accept pendingExternalUpdates", source.contains("pendingExternalUpdates: List<ExternalAppUpdate> = emptyList()"))
        assertTrue("SettingsScreen must accept onInstallSelfUpdate", source.contains("onInstallSelfUpdate: () -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallExternalUpdate", source.contains("onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallAll", source.contains("onInstallAll: () -> Unit = {}"))
        assertTrue("SettingsScreen must forward onInstallAll to the pane", source.contains("onInstallAll = onInstallAll"))
        assertTrue("Updates pane must render a Pending updates card", pane.contains("GroupCard(caption = \"Pending updates\")"))
        assertTrue("Updates pane must render an Update All button", pane.contains("Update All"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.SettingsPanesWiringTest" --tests "com.kkc.sheettracker.ui.settings.SettingsScreenPendingUpdatesWiringTest"`
Expected: FAIL — `missing branch for LOOK_AND_FEEL`, `SettingsCard( should be removed`, `must forward onInstallAll to the pane`.

- [ ] **Step 3: Rewrite `SettingsScreen.kt`**

Replace the whole file with the following. The parameter list (from `fun SettingsScreen(` through `) {`) is identical to the current file's lines 50-95 — copy it unchanged.

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.EmployeeDirectory
import com.kkc.sheettracker.data.IdlePowerSaveStore
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.ui.components.AdminPasswordDialog
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.icons.SettingsUpdatesSelected
import com.kkc.sheettracker.ui.settings.panes.AdminPane
import com.kkc.sheettracker.ui.settings.panes.LookAndFeelPane
import com.kkc.sheettracker.ui.settings.panes.MePane
import com.kkc.sheettracker.ui.settings.panes.PerformancePowerPane
import com.kkc.sheettracker.ui.settings.panes.SyncNetworkPane
import com.kkc.sheettracker.ui.settings.panes.TabletDataPane
import com.kkc.sheettracker.ui.settings.panes.UpdatesAboutPane
import com.kkc.sheettracker.ui.settings.panes.ViewersPane
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.theme.KKCThemeRepository
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import com.kkc.sheettracker.update.ExternalAppUpdate
import java.io.File

/** Clearance below the rail and pane so their last rows scroll above the floating navbar. */
private val NavbarClearance = 160.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean = true,
    darkThemeOverride: Boolean = false,
    workMode: WorkMode,
    flexibleModeEnabled: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit = {},
    onWorkModeChanged: (WorkMode) -> Unit,
    onFlexibleModeChanged: (Boolean) -> Unit,
    onReinstallLatest: () -> Unit,
    /** Re-scans for app updates; run each time Settings opens. */
    onCheckForUpdates: () -> Unit = {},
    onTabletIdChanged: (String) -> Unit,
    onBasePathChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    onBack: () -> Unit,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    useStandardSheets: Boolean = false,
    onUseStandardSheetsChanged: (Boolean) -> Unit = {},
    continuousScrollDefault: Boolean = false,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit = {},
    timecardConfig: TimecardServerConfig,
    adminSyncConfig: AdminSyncConfig,
    themeCatalog: KKCThemeCatalog = KKCThemeRepository.builtInCatalog(),
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit = {},
    onThemeOverrideChanged: (String?) -> Unit = {},
    onThemeCatalogReload: () -> Unit = {},
    onOpenAssemblyViewerDefaults: () -> Unit = {},
    onOpenSpecialtyViewerDefaults: () -> Unit = {},
    uiPreferencesStore: UiPreferencesStore,
    idlePowerSaveStore: IdlePowerSaveStore,
    pendingSelfUpdate: File? = null,
    pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),
    onInstallSelfUpdate: () -> Unit = {},
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},
    onInstallAll: () -> Unit = {},
) {
    val adminMode by AdminModeController.enabled.collectAsState()
    var showAdminDialog by remember { mutableStateOf(false) }

    // Checks that run each time Settings opens.
    LaunchedEffect(Unit) {
        EmployeeDirectory.refresh(File(basePath))
        onCheckForUpdates()
    }

    val updateCount = pendingUpdateCount(pendingSelfUpdate != null, pendingExternalUpdates.size)
    var section by rememberSaveable { mutableStateOf(initialSection(updateCount > 0)) }
    val wideChips = showWideChips(LocalConfiguration.current.screenWidthDp.toFloat())

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (tone, syncLabel) = syncChip(syncthingStatus.status)
                        StatusChip(syncLabel, onClick = { section = SettingsSection.SYNC_NETWORK }, dot = syncDotColor(tone))
                        if (updateCount > 0) {
                            StatusChip(
                                updatesChipLabel(updateCount),
                                onClick = { section = SettingsSection.UPDATES_ABOUT },
                                container = LocalKKCStatusColors.current.skipBg,
                                content = Color.Black.copy(alpha = 0.85f),
                                icon = SettingsUpdatesSelected
                            )
                        }
                        if (adminMode) {
                            StatusChip(
                                "Admin ON",
                                onClick = { section = SettingsSection.ADMIN },
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (wideChips) {
                            StatusChip("Tablet $tabletId", onClick = { section = SettingsSection.TABLET_DATA })
                            StatusChip("v${BuildConfig.VERSION_NAME}", onClick = { section = SettingsSection.UPDATES_ABOUT })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(top = 4.dp)
        ) {
            WorkModeRow(
                workMode = workMode,
                onWorkModeChanged = onWorkModeChanged,
                flexibleModeEnabled = flexibleModeEnabled,
                onFlexibleModeChanged = onFlexibleModeChanged
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsRail(
                    selected = section,
                    onSelect = { section = it },
                    updatesBadge = updateCount,
                    bottomClearance = NavbarClearance,
                    modifier = Modifier.fillMaxHeight()
                )
                // New scroll state per section so switching always starts at the top.
                val paneScroll = key(section) { rememberScrollState() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(paneScroll)
                        .padding(bottom = NavbarClearance),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionHeader(section)
                    when (section) {
                        SettingsSection.LOOK_AND_FEEL -> LookAndFeelPane(
                            isDarkTheme = isDarkTheme,
                            followSystemTheme = followSystemTheme,
                            darkThemeOverride = darkThemeOverride,
                            onFollowSystemThemeChanged = onFollowSystemThemeChanged,
                            onThemeChanged = onThemeChanged,
                            themeCatalog = themeCatalog,
                            onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                            onThemeOverrideChanged = onThemeOverrideChanged,
                            onThemeCatalogReload = onThemeCatalogReload
                        )
                        SettingsSection.VIEWERS -> ViewersPane(
                            isDarkTheme = isDarkTheme,
                            useStandardSheets = useStandardSheets,
                            onUseStandardSheetsChanged = onUseStandardSheetsChanged,
                            continuousScrollDefault = continuousScrollDefault,
                            onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
                            uiPreferencesStore = uiPreferencesStore,
                            onOpenAssemblyViewerDefaults = onOpenAssemblyViewerDefaults,
                            onOpenSpecialtyViewerDefaults = onOpenSpecialtyViewerDefaults
                        )
                        SettingsSection.ME -> MePane(
                            employeeName = employeeName,
                            onEmployeeNameChanged = onEmployeeNameChanged
                        )
                        SettingsSection.UPDATES_ABOUT -> UpdatesAboutPane(
                            pendingSelfUpdate = pendingSelfUpdate,
                            pendingExternalUpdates = pendingExternalUpdates,
                            onInstallSelfUpdate = onInstallSelfUpdate,
                            onInstallExternalUpdate = onInstallExternalUpdate,
                            onInstallAll = onInstallAll,
                            onCheckForUpdates = onCheckForUpdates,
                            isDebugBuild = isDebugBuild,
                            onReinstallLatest = onReinstallLatest
                        )
                        SettingsSection.TABLET_DATA -> TabletDataPane(
                            tabletId = tabletId,
                            onTabletIdChanged = onTabletIdChanged,
                            basePath = basePath,
                            onBasePathChanged = onBasePathChanged
                        )
                        SettingsSection.SYNC_NETWORK -> SyncNetworkPane(
                            syncthingApiKey = syncthingApiKey,
                            syncthingStatus = syncthingStatus,
                            onSyncthingApiKeySave = onSyncthingApiKeySave,
                            onSyncthingCheckNow = onSyncthingCheckNow,
                            onSyncthingStartNow = onSyncthingStartNow,
                            timecardConfig = timecardConfig,
                            adminSyncConfig = adminSyncConfig
                        )
                        SettingsSection.PERFORMANCE_POWER -> PerformancePowerPane(
                            uiPreferencesStore = uiPreferencesStore,
                            idlePowerSaveStore = idlePowerSaveStore
                        )
                        SettingsSection.ADMIN -> AdminPane(
                            adminMode = adminMode,
                            onUnlockRequested = { showAdminDialog = true }
                        )
                    }
                }
            }
        }
    }

    if (showAdminDialog) {
        AdminPasswordDialog(
            onUnlocked = {
                AdminModeController.setEnabled(true)
                showAdminDialog = false
            },
            onDismiss = { showAdminDialog = false }
        )
    }
}
```

The parameter list above is the current file's lines 51-94 verbatim — diff it against the old file before saving; it must not change. Delete the unused `fillMaxWidth` import if the compiler warns.

- [ ] **Step 4: Run the settings + navigation tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.settings.*" --tests "com.kkc.sheettracker.navigation.FlexibleModeWiringTest" --tests "com.kkc.sheettracker.navigation.PendingUpdatesSettingsWiringTest"`
Expected: PASS — all settings tests (Tasks 1-3, 7, `ThemePickerLogicTest`) and both navigation wiring tests.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsPanesWiringTest.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenPendingUpdatesWiringTest.kt
git commit -m "feat(settings): rail + pane settings layout with pinned work mode tiles"
```

---

### Task 8: Full verification, version bump, tablet install

**Files:**
- Modify: `app/build.gradle.kts:23-24`

**Interfaces:**
- Consumes: the finished feature.
- Produces: release APK 8.7.14 installed on connected tablets.

- [ ] **Step 1: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all pass except the known env-only `PdfMarkup` MotionEvent test. Any other failure is a regression — fix before continuing.

- [ ] **Step 2: Bump the version**

In `app/build.gradle.kts` change:

```kotlin
        versionCode = 80713
        versionName = "8.7.13"
```

to:

```kotlin
        versionCode = 80714
        versionName = "8.7.14"
```

- [ ] **Step 3: Build and install the release APK** (do not use `adb-install-release.ps1` — its Unicode breaks under `powershell -File`)

```powershell
.\gradlew.bat assembleRelease
adb devices
adb install -r app\build\outputs\apk\release\app-release.apk
```

Expected: `BUILD SUCCESSFUL`, then `Success` from adb for each tablet (repeat the install with `-s <serial>` per device when more than one is attached).

- [ ] **Step 4: Manual check on the tablet** (ask the user to open Settings and confirm; do not adb-tap through the app)

Portrait and landscape:
- Mode tiles square in portrait (~190dp), 168dp tall in landscape; tapping one changes work mode.
- Rail reaches Admin above the floating navbar; pane scrolls; switching sections scrolls the pane to top.
- Sync chip jumps to Sync & Network; Tablet/Version chips appear only in landscape.
- Theme swatch tap changes theme; NFL card search selects a team and the card shows its name; Use fleet default resets.
- Edit Tablet ID to spaces → Save disabled; Timeclock IP cleared → saves (auto discovery).
- Low-end toggle shows sub-toggles; admin unlock dialog opens and Admin ON chip appears.

- [ ] **Step 5: Commit**

```powershell
git add app/build.gradle.kts
git commit -m "chore: release Sheet Tracker 8.7.14 (settings facelift)"
```
