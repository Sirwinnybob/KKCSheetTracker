# Safety Committee Meeting Documents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a middle Safety Committee Meetings tab that lists and opens PDFs from `.safety/safety_meetings` on the configured network share.

**Architecture:** Keep folder and tab configuration in the pure `SafetyDocumentsScreenLogic` unit so it is directly testable. Reuse one private Compose PDF-list component for the general and meeting document tabs, while leaving the existing Safety Concerns workflow unchanged at its new third-tab index.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android `FileProvider`/viewer intents, JUnit 4, Gradle.

## Global Constraints

- Preserve all existing uncommitted edits, especially the admin-mode Safety Concerns access change in the same files.
- Do not add an external service or dependency.
- Keep the library read-only; the app must not create or modify `.safety/safety_meetings`.
- Show only regular `.pdf` files, matching the extension without regard to case, sorted by filename.
- Do not install or uninstall the Android app as part of this work.
- Do not edit `agent_docs/project_progress.md` or `agent_docs/latest_session_work.md`; this is Light-route leaf work.

---

### Task 1: Add and Verify the Safety Committee Meetings Tab

**Files:**
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreenLogicTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreenLogic.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreen.kt`

**Interfaces:**
- Consumes: `SafetyDocumentsScreen(basePath: String, onBack: () -> Unit)` and `SafetyDocumentsScreenLogic.listPdfs(directory: File): List<File>`.
- Produces: `SafetyDocumentsScreenLogic.meetingDocumentsDir(basePath: String): File` and `SafetyDocumentsScreenLogic.tabTitles: List<String>`.
- Preserves: `SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber: Boolean, adminMode: Boolean): Boolean` and all Safety Concerns dialogs, repository calls, and subscription behavior.

- [ ] **Step 1: Write the failing configuration and meeting-folder tests**

Add these tests to `SafetyDocumentsScreenLogicTest` without changing the existing admin-access test:

```kotlin
@Test
fun tabTitles_placesSafetyCommitteeMeetingsBetweenDocumentsAndConcerns() {
    assertEquals(
        listOf("Documents (PDFs)", "Safety Committee Meetings", "Safety Concerns"),
        SafetyDocumentsScreenLogic.tabTitles
    )
}

@Test
fun meetingDocumentsDir_resolvesUnderSafetyFolder() {
    val basePath = Files.createTempDirectory("safety-meetings-path").toFile()

    val result = SafetyDocumentsScreenLogic.meetingDocumentsDir(basePath.absolutePath)

    assertEquals(
        File(File(basePath, ".safety"), "safety_meetings").absolutePath,
        result.absolutePath
    )
}

@Test
fun listPdfs_readsOnlyMeetingPdfsSortedByName() {
    val basePath = Files.createTempDirectory("safety-meetings-list").toFile()
    val meetingsDir = File(File(basePath, ".safety"), "safety_meetings").apply { mkdirs() }
    File(meetingsDir, "2026-08 Meeting.PDF").writeText("x")
    File(meetingsDir, "2026-07 Meeting.pdf").writeText("x")
    File(meetingsDir, "agenda.txt").writeText("x")
    File(meetingsDir, "Archive.pdf").mkdirs()

    val result = SafetyDocumentsScreenLogic.listPdfs(
        SafetyDocumentsScreenLogic.meetingDocumentsDir(basePath.absolutePath)
    )

    assertEquals(
        listOf("2026-07 Meeting.pdf", "2026-08 Meeting.PDF"),
        result.map { it.name }
    )
}
```

- [ ] **Step 2: Run the focused tests and confirm the expected RED state**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.standards.SafetyDocumentsScreenLogicTest"
```

Expected: compilation fails because `tabTitles` and `meetingDocumentsDir` do not exist. This is the intended failing state; unrelated test or environment failures must be diagnosed before production code is changed.

- [ ] **Step 3: Add the minimal pure configuration**

Add the following members to `SafetyDocumentsScreenLogic`, retaining `listPdfs` and `hasSafetyConcernsAccess`:

```kotlin
val tabTitles = listOf(
    "Documents (PDFs)",
    "Safety Committee Meetings",
    "Safety Concerns"
)

fun meetingDocumentsDir(basePath: String): File =
    File(File(basePath, ".safety"), "safety_meetings")
```

- [ ] **Step 4: Run the focused tests and confirm the pure logic is GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.standards.SafetyDocumentsScreenLogicTest"
```

Expected: `SafetyDocumentsScreenLogicTest` passes with zero failures.

- [ ] **Step 5: Load both document collections in the Safety screen**

In `SafetyDocumentsScreen`, resolve and store the meeting folder beside the existing Safety folder:

```kotlin
val safetyDir = remember(basePath) { File(basePath, ".safety") }
val meetingDocumentsDir = remember(basePath) {
    SafetyDocumentsScreenLogic.meetingDocumentsDir(basePath)
}
```

Add meeting-document state:

```kotlin
var pdfFiles by remember { mutableStateOf<List<File>>(emptyList()) }
var meetingPdfFiles by remember { mutableStateOf<List<File>>(emptyList()) }
```

Extend `refreshData()` without changing the concerns refresh:

```kotlin
pdfFiles = withContext(Dispatchers.IO) { SafetyDocumentsScreenLogic.listPdfs(safetyDir) }
meetingPdfFiles = withContext(Dispatchers.IO) {
    SafetyDocumentsScreenLogic.listPdfs(meetingDocumentsDir)
}
concerns = withContext(Dispatchers.IO) { repository.getConcerns() }
```

- [ ] **Step 6: Render exactly three tabs from the tested titles**

Replace the two manually declared tabs with an indexed loop over `SafetyDocumentsScreenLogic.tabTitles`. Use the existing PDF icon for indices 0 and 1, and the warning icon for index 2:

```kotlin
TabRow(selectedTabIndex = selectedTab) {
    SafetyDocumentsScreenLogic.tabTitles.forEachIndexed { index, title ->
        Tab(
            selected = selectedTab == index,
            onClick = { selectedTab = index },
            text = { Text(title) },
            icon = {
                Icon(
                    imageVector = if (index == 2) Icons.Filled.Warning else Icons.Filled.PictureAsPdf,
                    contentDescription = null
                )
            }
        )
    }
}
```

- [ ] **Step 7: Extract the shared PDF-list presentation**

Add a private composable below `SafetyDocumentsScreen` that receives data and a click callback but performs no folder access:

```kotlin
@Composable
private fun SafetyPdfList(
    files: List<File>,
    emptyMessage: String,
    onOpenPdf: (File) -> Unit
) {
    if (files.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = emptyMessage, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            items(files, key = { it.absolutePath }) { file ->
                ListItem(
                    headlineContent = { Text(file.nameWithoutExtension) },
                    leadingContent = {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onOpenPdf(file) }
                )
            }
        }
    }
}
```

Inside `SafetyDocumentsScreen`, add one local callback containing the existing `FileProvider` and viewer-intent code:

```kotlin
fun openPdf(file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("KKC", "Failed to open safety document: ${file.absolutePath}", e)
    }
}
```

- [ ] **Step 8: Route the three tab bodies without changing Safety Concerns**

Use an explicit `when` so the third tab cannot accidentally fall through from a future index:

```kotlin
when (selectedTab) {
    0 -> SafetyPdfList(
        files = pdfFiles,
        emptyMessage = "No safety documents found.",
        onOpenPdf = ::openPdf
    )
    1 -> SafetyPdfList(
        files = meetingPdfFiles,
        emptyMessage = "No safety committee meeting documents found.",
        onOpenPdf = ::openPdf
    )
    2 -> {
        // Keep the existing locked/subscriber Safety Concerns body unchanged here.
    }
}
```

Move the existing Safety Concerns branch into case 2 without altering its contents. Update the file-level comment to document the three-tab order.

- [ ] **Step 9: Re-run focused tests after the UI refactor**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.standards.SafetyDocumentsScreenLogicTest"
```

Expected: all focused tests pass with zero failures.

- [ ] **Step 10: Build the debug APK**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Do not run `adb install`, `adb uninstall`, or any deployment command.

- [ ] **Step 11: Review the scoped diff and preserve pre-existing work**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreen.kt app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreenLogic.kt app/src/test/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreenLogicTest.kt
```

Confirm that the previously present `hasSafetyConcernsAccess` implementation and its test remain intact, the meeting path is exactly `.safety/safety_meetings`, and no unrelated files were changed by this task.

- [ ] **Step 12: Commit only when the user requests an implementation commit**

The target files already contain user-owned uncommitted edits. Do not stage or commit them automatically. If the user later requests a commit, inspect and confirm the combined scope before staging these files.
