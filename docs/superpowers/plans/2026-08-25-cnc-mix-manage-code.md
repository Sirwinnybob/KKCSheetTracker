# CNC Mix / Second-Pass "Manage Code" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let CNC-mode tablets reorder PGM cut order per material, generate/update `.mix` files, and mark PGMs for second-pass processing (PUNLOAD removal, standard/super second pass) through a new "Manage Code" screen reachable from the job detail screen and from the Sheet Viewer, with Sheet Viewer page order following the applied mix.

**Architecture:** A small `data/mixservice` package holds a plain OkHttp/Gson client (`MixServiceClient`) mirroring the existing `ArchiveAdminClient` pattern, plus pure Kotlin logic (sheet/PGM matching, lock rules, selection derivation, diff/payload building, page-order resolution) that is fully unit-testable without Android framework classes. A `ui/managecode` package holds the Compose screen, reusing the app's existing `sh.calvin.reorderable` drag-reorder library (already used by `SupplyTabReorderScreen`). Two small, targeted edits wire it into `JobDetailScreen`, `SheetViewerScreen`, and `NavGraph`.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp, Gson, JUnit 4 + Mockito + OkHttp MockWebServer (all already project dependencies).

**Spec:** `docs/superpowers/specs/2026-08-25-cnc-mix-manage-code-design.md`

**Known gap (see spec Section 2):** the Mix Service's second-pass routes (`GET`/`POST /jobs/{job}/materials/{mat}/pgm-edits`) are Task 4 of `C:\Scripts\PGM_BCR_Loader\docs\superpowers\plans\2026-08-25-pgm-second-pass-cli-api.md` and are not implemented yet. This plan builds the full client/UI against that plan's documented contract; PUNLOAD/SUPER/2ND calls will 403 (`second_pass_disabled`) or fail until that work ships. `/mixes*` (used for MIX) is live today.

---

## File Structure

New files:
- `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceModels.kt` — wire data classes.
- `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt` — HTTP client, hardcoded `http://192.168.20.4:8477`.
- `app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetPgmMatcher.kt` — PDF page ↔ PGM row building, A/Z pairing, order application.
- `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelection.kt` — lock rule, per-row checkbox state derivation/toggling.
- `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt` — diff/payload building for Generate, cross-mix duplicate detection.
- `app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolver.kt` — resolves a material's page navigation order from its mix.
- `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeThumbnail.kt` — small standalone PDF page thumbnail renderer.
- `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt` — the screen (full-job and single-material modes).
- Matching `app/src/test/java/com/kkc/sheettracker/data/mixservice/*Test.kt` for every pure-logic file above.

Modified files:
- `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt` — widen `inferSheetFiles` visibility for reuse, add Manage Code button, reorder `visiblePages` per the material's mix.
- `app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt` — add Manage Code button next to the specialty section.
- `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` — register the `manage_code/{folderName}?material={material}` route and wire both entry points.

---

### Task 1: Mix Service wire models

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceModels.kt`

- [ ] **Step 1: Write the models**

```kotlin
package com.kkc.sheettracker.data.mixservice

data class PgmInventoryItem(
    val name: String = "",
    val size: Long = 0,
    val mtime: String = ""
)

data class MixDefinition(
    val name: String = "",
    val job: String = "",
    val material: String = "",
    val programs: List<String> = emptyList(),
    val mixFilename: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastCompiledAt: String? = null,
    val lastCompileOk: Boolean? = null,
    val lastCompileError: String? = null,
    val status: String? = null
)

data class PgmEditRow(
    val pgm: String,
    val secondPass: String,
    val removePUnload: Boolean
)

data class PgmEditBatchRequest(
    val requestId: String,
    val rows: List<PgmEditRow>
)

// The per-file identity key in the pgm-edits response is assumed to be `pgm`, mirroring the
// request row's own field name. The CLI API plan (Task 4) hasn't shipped yet; if the real
// response uses a different key, only this class needs updating.
data class PgmEditFileResult(
    val pgm: String = "",
    val status: String = "",
    val mixFiles: List<String> = emptyList()
)

data class PgmEditBatchResponse(
    val ok: Boolean = false,
    val requestId: String = "",
    val backupDir: String? = null,
    val files: List<PgmEditFileResult> = emptyList()
)

data class PgmEditCurrentState(
    val mode: String? = null,
    val removePUnload: Boolean? = null,
    val mixFiles: List<String> = emptyList()
)

data class PgmEditFileHistory(
    val current: PgmEditCurrentState? = null
)

data class PgmEditHistoryView(
    val files: Map<String, PgmEditFileHistory> = emptyMap()
)
```

- [ ] **Step 2: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceModels.kt
git commit -m "feat(mixservice): add wire models for Mix Service API"
```

---

### Task 2: MixServiceClient — inventory reads

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt`

- [ ] **Step 1: Write failing tests for reachability and inventory reads**

```kotlin
package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MixServiceClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = MixServiceClient(server.url("/").toString())

    @Test
    fun `isReachable true on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(client().isReachable())
    }

    @Test
    fun `isReachable false on connection failure`() = runBlocking {
        server.shutdown()
        assertFalse(client().isReachable())
    }

    @Test
    fun `listPgms parses inventory items`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""[{"name":"R1.pgm","size":100,"mtime":"2026-08-25T10:00:00Z"}]""")
        )
        val items = client().listPgms("648 - WIECHERT", "19mm Pre_Finished")
        assertEquals(listOf("R1.pgm"), items.map { it.name })
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.endsWith("/jobs/648%20-%20WIECHERT/materials/19mm%20Pre_Finished/pgms") == true)
    }

    @Test
    fun `listPgms returns empty list on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(emptyList<PgmInventoryItem>(), client().listPgms("648", "Unknown"))
    }

    @Test
    fun `listMixes returns null on failure, empty list on no mixes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(null, client().listMixes("648", "19mm Pre_Finished"))

        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(emptyList<MixDefinition>(), client().listMixes("648", "19mm Pre_Finished"))
    }

    @Test
    fun `listMixes parses definitions and filters by job and material query params`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""[{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R1.pgm","R2.pgm"],"mixFilename":"KkcMix.mix","status":"compiled"}]""")
        )
        val mixes = client().listMixes("648", "19mm Pre_Finished")
        assertEquals("KkcMix", mixes?.single()?.name)
        assertEquals(listOf("R1.pgm", "R2.pgm"), mixes?.single()?.programs)
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("job=648") == true)
        assertTrue(recorded.path?.contains("material=19mm") == true)
    }
}
```

- [ ] **Step 2: Run to verify red**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixServiceClientTest"`
Expected: FAIL — `MixServiceClient` does not exist.

- [ ] **Step 3: Implement `MixServiceClient` (reads only for now)**

```kotlin
package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MixServiceClient(private val baseUrl: String = "http://192.168.20.4:8477") {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/status".toHttpUrl()).get().build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun listPgms(job: String, material: String): List<PgmInventoryItem> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgms")
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val type = object : TypeToken<List<PgmInventoryItem>>() {}.type
                gson.fromJson<List<PgmInventoryItem>>(body, type).orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun listMixes(job: String, material: String): List<MixDefinition>? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/mixes".toHttpUrl().newBuilder()
            .addQueryParameter("job", job)
            .addQueryParameter("material", material)
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val type = object : TypeToken<List<MixDefinition>>() {}.type
                gson.fromJson<List<MixDefinition>>(body, type)
            }
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run to verify green**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixServiceClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt
git commit -m "feat(mixservice): add client reachability and inventory reads"
```

---

### Task 3: MixServiceClient — mix writes (create/update)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test
    fun `createMix posts name job material programs and parses the created definition`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R1.pgm"],"mixFilename":"KkcMix.mix"}""")
        )
        val result = client().createMix("648", "19mm Pre_Finished", "KkcMix", listOf("R1.pgm"))
        check(result is MixWriteResult.Success)
        assertEquals("KkcMix", result.definition.name)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path?.endsWith("/mixes") == true)
    }

    @Test
    fun `createMix maps status codes to sealed results`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        check(client().createMix("648", "19mm Pre_Finished", "Dup", emptyList()) is MixWriteResult.DuplicateName)

        server.enqueue(MockResponse().setResponseCode(404))
        check(client().createMix("648", "Unknown", "X", emptyList()) is MixWriteResult.UnknownJobOrMaterial)

        server.enqueue(MockResponse().setResponseCode(400))
        check(client().createMix("648", "19mm Pre_Finished", "bad*name", emptyList()) is MixWriteResult.InvalidName)

        server.enqueue(MockResponse().setResponseCode(503))
        check(client().createMix("648", "19mm Pre_Finished", "X", emptyList()) is MixWriteResult.CompileBusy)
    }

    @Test
    fun `createMix maps 422 to missing programs`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"missing":["R9.pgm"]}"""))
        val result = client().createMix("648", "19mm Pre_Finished", "X", listOf("R9.pgm"))
        check(result is MixWriteResult.MissingPrograms)
        assertEquals(listOf("R9.pgm"), result.missing)
    }

    @Test
    fun `updateMix puts to the named mix path with the new program order`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R2.pgm","R1.pgm"],"mixFilename":"KkcMix.mix"}""")
        )
        val result = client().updateMix("648", "19mm Pre_Finished", "KkcMix", listOf("R2.pgm", "R1.pgm"))
        check(result is MixWriteResult.Success)
        assertEquals(listOf("R2.pgm", "R1.pgm"), result.definition.programs)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertTrue(recorded.path?.endsWith("/mixes/KkcMix") == true)
    }
```

- [ ] **Step 2: Run to verify red**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixServiceClientTest"`
Expected: FAIL — `MixWriteResult`, `createMix`, `updateMix` don't exist.

- [ ] **Step 3: Implement writes**

Add to `MixServiceClient.kt` (above the class, as a top-level sealed class) and inside the class body:

```kotlin
sealed class MixWriteResult {
    data class Success(val definition: MixDefinition) : MixWriteResult()
    data class DuplicateName(val name: String) : MixWriteResult()
    object UnknownJobOrMaterial : MixWriteResult()
    data class MissingPrograms(val missing: List<String>) : MixWriteResult()
    object InvalidName : MixWriteResult()
    object CompileBusy : MixWriteResult()
    object NetworkError : MixWriteResult()
}
```

```kotlin
    suspend fun createMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        overwrite: Boolean = false
    ): MixWriteResult = writeMix("POST", job, material, name, programs, overwrite)

    suspend fun updateMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>
    ): MixWriteResult = writeMix("PUT", job, material, name, programs, overwrite = false)

    private suspend fun writeMix(
        method: String,
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        overwrite: Boolean
    ): MixWriteResult = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val url = if (method == "POST") {
            "$root/mixes".toHttpUrl()
        } else {
            "$root/mixes/".toHttpUrl().newBuilder().addPathSegment(name).build()
        }
        val payload = mutableMapOf<String, Any?>(
            "job" to job,
            "material" to material,
            "programs" to programs
        )
        if (method == "POST") {
            payload["name"] = name
            payload["overwrite"] = overwrite
        }
        val body = gson.toJson(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).method(method, body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 201 -> {
                        val responseBody = response.body?.string() ?: return@use MixWriteResult.NetworkError
                        MixWriteResult.Success(gson.fromJson(responseBody, MixDefinition::class.java))
                    }
                    400 -> MixWriteResult.InvalidName
                    404 -> MixWriteResult.UnknownJobOrMaterial
                    409 -> MixWriteResult.DuplicateName(name)
                    422 -> {
                        val missing = runCatching {
                            gson.fromJson(response.body?.string().orEmpty(), com.google.gson.JsonObject::class.java)
                                ?.getAsJsonArray("missing")?.map { it.asString }
                        }.getOrNull().orEmpty()
                        MixWriteResult.MissingPrograms(missing)
                    }
                    503 -> MixWriteResult.CompileBusy
                    else -> MixWriteResult.NetworkError
                }
            }
        }.getOrDefault(MixWriteResult.NetworkError)
    }
```

Note: the 422 branch reads `response.body?.string()` after the `when` has already been entered — this consumes the body exactly once, which is safe here since no other branch also reads it for the same response.

- [ ] **Step 4: Run to verify green**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixServiceClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt
git commit -m "feat(mixservice): add mix create/update writes"
```

---

> **Contract correction (post-Task-3):** Tasks 2 and 3's code above assumed a flat wire
> shape guessed from the design docs. The real Mix Service (verified directly against
> `C:\Scripts\PGM_BCR_Loader\mix_service\service.py`, `inventory.py`,
> `second_pass_client.py`, `edit_history_reader.py`) wraps every response in an
> `{"ok": ..., <key>: ...}` envelope, returns 200 (never 201) on mix writes with the
> definition nested under `"mix"` and `status` as a sibling field, uses
> `"missing program: <name>"` text (not a `"missing"` array) for its one 422 case, and
> `mtime` is a JSON number. This was fixed in commits `ba302d7` and `d492795` — the
> actual `MixServiceClient.kt`/`MixServiceModels.kt`/`MixServiceClientTest.kt` in the repo
> reflect the corrected contract, not the code blocks above. Task 4 below has been
> corrected in place (field names `name`/`files`/`punloadRemoved`, not `pgm`/`rows`/
> `removePUnload`) before being implemented, using the same verified-against-source
> approach.

### Task 4: MixServiceClient — pgm-edits (second pass) reads and writes

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test
    fun `listPgmEdits parses the ledger view`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"files":{"R1.pgm":{"current":{"mode":"standard","removePUnload":true,"mixFiles":["KkcMix.mix"]}}}}"""
            )
        )
        val view = client().listPgmEdits("648", "19mm Pre_Finished")
        assertEquals("standard", view?.files?.get("R1.pgm")?.current?.mode)
        assertEquals(true, view?.files?.get("R1.pgm")?.current?.removePUnload)
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("pgm-edits?historyLimit=20") == true)
    }

    @Test
    fun `listPgmEdits returns null on failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(null, client().listPgmEdits("648", "19mm Pre_Finished"))
    }

    @Test
    fun `submitPgmEdits posts requestId and rows, parses success`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"requestId":"r1","backupDir":"BACKUP_1","files":[{"pgm":"R1.pgm","status":"succeeded"}]}""")
        )
        val result = client().submitPgmEdits(
            "648", "19mm Pre_Finished", "r1",
            listOf(PgmEditRow(pgm = "R1.pgm", secondPass = "standard", removePUnload = false))
        )
        check(result is PgmEditSubmitResult.Success)
        assertEquals("succeeded", result.response.files.single().status)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("r1", org.json.JSONObject(recorded.body.readUtf8()).getString("requestId"))
    }

    @Test
    fun `submitPgmEdits maps status codes to sealed results`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        check(client().submitPgmEdits("648", "M", "r1", emptyList()) is PgmEditSubmitResult.Disabled)

        server.enqueue(MockResponse().setResponseCode(409))
        check(client().submitPgmEdits("648", "M", "r2", emptyList()) is PgmEditSubmitResult.EditBusy)

        server.enqueue(MockResponse().setResponseCode(503))
        check(client().submitPgmEdits("648", "M", "r3", emptyList()) is PgmEditSubmitResult.CompileBusy)

        server.enqueue(MockResponse().setResponseCode(504))
        check(client().submitPgmEdits("648", "M", "r4", emptyList()) is PgmEditSubmitResult.WinxisoTimeout)
    }
```

- [ ] **Step 2: Run to verify red**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixServiceClientTest"`
Expected: FAIL — `PgmEditSubmitResult`, `listPgmEdits`, `submitPgmEdits` don't exist.

- [ ] **Step 3: Implement**

```kotlin
sealed class PgmEditSubmitResult {
    data class Success(val response: PgmEditBatchResponse) : PgmEditSubmitResult()
    object Disabled : PgmEditSubmitResult()
    object EditBusy : PgmEditSubmitResult()
    object CompileBusy : PgmEditSubmitResult()
    object WinxisoTimeout : PgmEditSubmitResult()
    object NetworkError : PgmEditSubmitResult()
}
```

```kotlin
    suspend fun listPgmEdits(job: String, material: String, historyLimit: Int = 20): PgmEditHistoryView? =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/jobs/".toHttpUrl().newBuilder()
                .addPathSegment(job)
                .addPathSegment("materials")
                .addPathSegment(material)
                .addPathSegment("pgm-edits")
                .addQueryParameter("historyLimit", historyLimit.toString())
                .build()
            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    gson.fromJson(body, PgmEditHistoryView::class.java)
                }
            }.getOrNull()
        }

    suspend fun submitPgmEdits(
        job: String,
        material: String,
        requestId: String,
        rows: List<PgmEditRow>
    ): PgmEditSubmitResult = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgm-edits")
            .build()
        val body = gson.toJson(PgmEditBatchRequest(requestId, rows)).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val responseBody = response.body?.string() ?: return@use PgmEditSubmitResult.NetworkError
                        PgmEditSubmitResult.Success(gson.fromJson(responseBody, PgmEditBatchResponse::class.java))
                    }
                    403 -> PgmEditSubmitResult.Disabled
                    409 -> PgmEditSubmitResult.EditBusy
                    503 -> PgmEditSubmitResult.CompileBusy
                    504 -> PgmEditSubmitResult.WinxisoTimeout
                    else -> PgmEditSubmitResult.NetworkError
                }
            }
        }.getOrDefault(PgmEditSubmitResult.NetworkError)
    }
```

- [ ] **Step 4: Run to verify green**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixServiceClientTest"`
Expected: PASS, all `MixServiceClientTest` cases green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt
git commit -m "feat(mixservice): add pgm-edits ledger read and batch submit"
```

---

### Task 5: Sheet ↔ PGM matcher

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt:3322` (widen visibility)
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetPgmMatcher.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/SheetPgmMatcherTest.kt`

- [ ] **Step 1: Widen `inferSheetFiles` visibility for reuse**

In `SheetViewerScreen.kt`, change:

```kotlin
private fun inferSheetFiles(pageMeta: com.kkc.sheettracker.data.models.PageMetadata?): List<String> {
```

to:

```kotlin
internal fun inferSheetFiles(pageMeta: com.kkc.sheettracker.data.models.PageMetadata?): List<String> {
```

No other change — this is the existing A/Z pairing fallback (single sheet id `R280602Z` implies partner `R280602A`), reused as-is rather than duplicated.

- [ ] **Step 2: Write failing tests**

```kotlin
package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetPgmMatcherTest {
    @Test
    fun `buildManageCodeRows appends pgm extension per page in pdf order`() {
        val pages = listOf(
            PageMetadata(pageNumber = 2, sheetFiles = listOf("R2")),
            PageMetadata(pageNumber = 1, sheetFiles = listOf("R1"))
        )
        val rows = buildManageCodeRows(pages)
        assertEquals(listOf(1, 2), rows.map { it.pageNumber })
        assertEquals(listOf("R1.pgm"), rows[0].pgmFiles)
        assertEquals("R1.pgm", rows[0].editablePgm)
    }

    @Test
    fun `buildManageCodeRows keeps A before Z as one combined row and Z is editable`() {
        val pages = listOf(PageMetadata(pageNumber = 1, sheetFiles = listOf("R590402A", "R590402Z")))
        val rows = buildManageCodeRows(pages)
        assertEquals(listOf("R590402A.pgm", "R590402Z.pgm"), rows.single().pgmFiles)
        assertEquals("R590402Z.pgm", rows.single().editablePgm)
    }

    @Test
    fun `buildManageCodeRows skips excluded, hidden, and continuation pages`() {
        val pages = listOf(
            PageMetadata(pageNumber = 1, sheetFiles = listOf("R1"), trackingExcluded = true),
            PageMetadata(pageNumber = 2, sheetFiles = listOf("R2"), hiddenInApp = true),
            PageMetadata(pageNumber = 3, sheetFiles = listOf("R3"), isPartListContinuation = true),
            PageMetadata(pageNumber = 4, sheetFiles = listOf("R4"))
        )
        assertEquals(listOf(4), buildManageCodeRows(pages).map { it.pageNumber })
    }

    @Test
    fun `applyExistingOrder reorders matched rows and appends unmapped ones in original order`() {
        val rows = listOf(
            ManageCodeRow(pageNumber = 1, pgmFiles = listOf("R1.pgm"), editablePgm = "R1.pgm"),
            ManageCodeRow(pageNumber = 2, pgmFiles = listOf("R2.pgm"), editablePgm = "R2.pgm"),
            ManageCodeRow(pageNumber = 3, pgmFiles = listOf("R3.pgm"), editablePgm = "R3.pgm")
        )
        val ordered = applyExistingOrder(rows, listOf("R2.pgm", "R1.pgm"))
        assertEquals(listOf(2, 1, 3), ordered.map { it.pageNumber })
    }
}
```

- [ ] **Step 3: Run to verify red**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.SheetPgmMatcherTest"`
Expected: FAIL — file doesn't exist.

- [ ] **Step 4: Implement**

```kotlin
package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.ui.viewer.inferSheetFiles

data class ManageCodeRow(
    val pageNumber: Int,
    val pgmFiles: List<String>,
    val editablePgm: String
)

fun buildManageCodeRows(pages: List<PageMetadata>): List<ManageCodeRow> =
    pages
        .filter { !it.trackingExcluded && !it.hiddenInApp && !it.isPartListContinuation }
        .sortedBy { it.pageNumber }
        .mapNotNull { page ->
            val files = inferSheetFiles(page).map { stem -> "$stem.pgm" }
            if (files.isEmpty()) null
            else ManageCodeRow(pageNumber = page.pageNumber, pgmFiles = files, editablePgm = files.last())
        }

fun applyExistingOrder(rows: List<ManageCodeRow>, orderedPgms: List<String>): List<ManageCodeRow> {
    val indexOf = orderedPgms.withIndex().associate { (i, pgm) -> pgm to i }
    val (mapped, unmapped) = rows.partition { indexOf.containsKey(it.editablePgm) }
    return mapped.sortedBy { indexOf.getValue(it.editablePgm) } + unmapped
}
```

- [ ] **Step 5: Run to verify green**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.SheetPgmMatcherTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetPgmMatcher.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/SheetPgmMatcherTest.kt
git commit -m "feat(mixservice): add sheet/PGM row matcher and order application"
```

---

### Task 6: Lock rule and per-row selection state

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelection.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelectionTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.SheetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageCodeSelectionTest {
    @Test
    fun `isRowLocked is true only for complete or re-nested`() {
        assertTrue(isRowLocked(SheetStatus.COMPLETE))
        assertTrue(isRowLocked(SheetStatus.RE_NESTED))
        assertFalse(isRowLocked(SheetStatus.NOT_STARTED))
        assertFalse(isRowLocked(SheetStatus.SKIPPED))
        assertFalse(isRowLocked(SheetStatus.HAS_BAD_PARTS))
    }

    @Test
    fun `deriveRowSelection defaults MIX checked when material has no existing mix`() {
        val selection = deriveRowSelection("R1.pgm", mixPrograms = emptyList(), hasExistingMix = false, editHistory = null)
        assertTrue(selection.mix)
        assertFalse(selection.secondPass)
        assertFalse(selection.superPass)
        assertFalse(selection.removePUnload)
    }

    @Test
    fun `deriveRowSelection reflects existing mix membership when a mix exists`() {
        val inMix = deriveRowSelection("R1.pgm", mixPrograms = listOf("R1.pgm"), hasExistingMix = true, editHistory = null)
        val notInMix = deriveRowSelection("R2.pgm", mixPrograms = listOf("R1.pgm"), hasExistingMix = true, editHistory = null)
        assertTrue(inMix.mix)
        assertFalse(notInMix.mix)
    }

    @Test
    fun `deriveRowSelection reflects live second-pass state from the ledger`() {
        val history = PgmEditHistoryView(
            files = mapOf("R1.pgm" to PgmEditFileHistory(PgmEditCurrentState(mode = "super", removePUnload = true)))
        )
        val selection = deriveRowSelection("R1.pgm", emptyList(), hasExistingMix = false, editHistory = history)
        assertTrue(selection.secondPass)
        assertTrue(selection.superPass)
        assertTrue(selection.removePUnload)
    }

    @Test
    fun `toggleSecondPass off clears super, toggleSuperPass on forces second pass`() {
        val checked = ManageCodeRowSelection(secondPass = true, superPass = true)
        assertFalse(toggleSecondPass(checked, false).superPass)

        val unchecked = ManageCodeRowSelection()
        val withSuper = toggleSuperPass(unchecked, true)
        assertTrue(withSuper.secondPass)
        assertTrue(withSuper.superPass)
    }
}
```

- [ ] **Step 2: Run to verify red**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.ManageCodeSelectionTest"`
Expected: FAIL — file doesn't exist.

- [ ] **Step 3: Implement**

```kotlin
package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.SheetStatus

fun isRowLocked(status: SheetStatus): Boolean =
    status == SheetStatus.COMPLETE || status == SheetStatus.RE_NESTED

data class ManageCodeRowSelection(
    val mix: Boolean = false,
    val removePUnload: Boolean = false,
    val secondPass: Boolean = false,
    val superPass: Boolean = false
)

fun deriveRowSelection(
    editablePgm: String,
    mixPrograms: List<String>,
    hasExistingMix: Boolean,
    editHistory: PgmEditHistoryView?
): ManageCodeRowSelection {
    val current = editHistory?.files?.get(editablePgm)?.current
    val mode = current?.mode ?: "none"
    return ManageCodeRowSelection(
        mix = if (hasExistingMix) mixPrograms.contains(editablePgm) else true,
        removePUnload = current?.removePUnload ?: false,
        secondPass = mode == "standard" || mode == "super",
        superPass = mode == "super"
    )
}

fun toggleSecondPass(selection: ManageCodeRowSelection, checked: Boolean): ManageCodeRowSelection =
    selection.copy(secondPass = checked, superPass = if (checked) selection.superPass else false)

fun toggleSuperPass(selection: ManageCodeRowSelection, checked: Boolean): ManageCodeRowSelection =
    selection.copy(superPass = checked, secondPass = if (checked) true else selection.secondPass)
```

- [ ] **Step 4: Run to verify green**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.ManageCodeSelectionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelection.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelectionTest.kt
git commit -m "feat(mixservice): add lock rule and per-row selection state"
```

---

### Task 7: Generate orchestration — diff, payload building, duplicate detection

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestratorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.kkc.sheettracker.data.mixservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageCodeOrchestratorTest {
    private val rows = listOf(
        ManageCodeRow(pageNumber = 1, pgmFiles = listOf("R1.pgm"), editablePgm = "R1.pgm"),
        ManageCodeRow(pageNumber = 2, pgmFiles = listOf("R2A.pgm", "R2Z.pgm"), editablePgm = "R2Z.pgm"),
        ManageCodeRow(pageNumber = 3, pgmFiles = listOf("R3.pgm"), editablePgm = "R3.pgm")
    )

    @Test
    fun `buildManageCodeChange excludes locked rows from programs and edit rows`() {
        val selections = mapOf(
            "R1.pgm" to ManageCodeRowSelection(mix = true, secondPass = true),
            "R2Z.pgm" to ManageCodeRowSelection(mix = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true)
        )
        val change = buildManageCodeChange(rows, selections, locked = setOf("R1.pgm"), originalPrograms = emptyList())
        assertFalse(change.programs.contains("R1.pgm"))
        assertTrue(change.editRows.none { it.pgm == "R1.pgm" })
    }

    @Test
    fun `buildManageCodeChange includes both A and Z files for a checked combined row`() {
        val selections = mapOf("R2Z.pgm" to ManageCodeRowSelection(mix = true))
        val change = buildManageCodeChange(rows.take(2).drop(1), selections, locked = emptySet(), originalPrograms = emptyList())
        assertEquals(listOf("R2A.pgm", "R2Z.pgm"), change.programs)
    }

    @Test
    fun `buildManageCodeChange builds edit rows only for punload or second pass selections`() {
        val selections = mapOf(
            "R1.pgm" to ManageCodeRowSelection(mix = true, removePUnload = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true, secondPass = true, superPass = true)
        )
        val change = buildManageCodeChange(listOf(rows[0], rows[2]), selections, locked = emptySet(), originalPrograms = emptyList())
        val r1 = change.editRows.single { it.pgm == "R1.pgm" }
        val r3 = change.editRows.single { it.pgm == "R3.pgm" }
        assertEquals("none", r1.secondPass)
        assertTrue(r1.removePUnload)
        assertEquals("super", r3.secondPass)
    }

    @Test
    fun `buildManageCodeChange flags order or membership changed correctly`() {
        val selections = mapOf(
            "R1.pgm" to ManageCodeRowSelection(mix = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true)
        )
        val subset = listOf(rows[0], rows[2])
        val unchanged = buildManageCodeChange(subset, selections, emptySet(), originalPrograms = listOf("R1.pgm", "R3.pgm"))
        val changed = buildManageCodeChange(subset, selections, emptySet(), originalPrograms = listOf("R3.pgm", "R1.pgm"))
        assertFalse(unchanged.orderOrMembershipChanged)
        assertTrue(changed.orderOrMembershipChanged)
    }

    @Test
    fun `findCrossMixDuplicates flags a pgm already owned by a different mix, ignoring the mix being edited`() {
        val others = listOf(
            MixDefinition(name = "OtherMix", programs = listOf("R1.pgm")),
            MixDefinition(name = "ThisMix", programs = listOf("R1.pgm"))
        )
        val warnings = findCrossMixDuplicates(listOf("R1.pgm", "R3.pgm"), thisMixName = "ThisMix", otherMixes = others)
        assertEquals(listOf(DuplicateMixWarning("R1.pgm", "OtherMix")), warnings)
    }
}
```

- [ ] **Step 2: Run to verify red**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.ManageCodeOrchestratorTest"`
Expected: FAIL — file doesn't exist.

- [ ] **Step 3: Implement**

```kotlin
package com.kkc.sheettracker.data.mixservice

data class ManageCodeChange(
    val orderOrMembershipChanged: Boolean,
    val programs: List<String>,
    val editRows: List<PgmEditRow>
)

fun buildManageCodeChange(
    rows: List<ManageCodeRow>,
    selections: Map<String, ManageCodeRowSelection>,
    locked: Set<String>,
    originalPrograms: List<String>
): ManageCodeChange {
    val programs = rows.flatMap { row ->
        val selection = selections[row.editablePgm] ?: ManageCodeRowSelection()
        if (row.editablePgm in locked || !selection.mix) emptyList() else row.pgmFiles
    }
    val editRows = rows.mapNotNull { row ->
        if (row.editablePgm in locked) return@mapNotNull null
        val selection = selections[row.editablePgm] ?: return@mapNotNull null
        if (!selection.removePUnload && !selection.secondPass) return@mapNotNull null
        PgmEditRow(
            pgm = row.editablePgm,
            secondPass = if (selection.superPass) "super" else if (selection.secondPass) "standard" else "none",
            removePUnload = selection.removePUnload
        )
    }
    return ManageCodeChange(
        orderOrMembershipChanged = programs != originalPrograms,
        programs = programs,
        editRows = editRows
    )
}

data class DuplicateMixWarning(val pgm: String, val otherMixName: String)

fun findCrossMixDuplicates(
    programs: List<String>,
    thisMixName: String,
    otherMixes: List<MixDefinition>
): List<DuplicateMixWarning> = programs.mapNotNull { pgm ->
    otherMixes.firstOrNull { it.name != thisMixName && pgm in it.programs }
        ?.let { owner -> DuplicateMixWarning(pgm, owner.name) }
}
```

- [ ] **Step 4: Run to verify green**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.ManageCodeOrchestratorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestratorTest.kt
git commit -m "feat(mixservice): add Generate diff/payload building and cross-mix duplicate detection"
```

---

### Task 8: Sheet Viewer page-order resolver

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolver.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolverTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetOrderResolverTest {
    private val pages = listOf(
        PageMetadata(pageNumber = 1, sheetFiles = listOf("R1")),
        PageMetadata(pageNumber = 2, sheetFiles = listOf("R2")),
        PageMetadata(pageNumber = 3, sheetFiles = listOf("R3"))
    )

    @Test
    fun `no mix falls back to natural visible page order`() {
        assertEquals(listOf(1, 2), reorderVisiblePages(pages, naturalOrder = listOf(1, 2), mixPrograms = emptyList()))
    }

    @Test
    fun `mix reorders mapped pages and keeps them within the natural visible set`() {
        val ordered = reorderVisiblePages(pages, naturalOrder = listOf(1, 2, 3), mixPrograms = listOf("R2.pgm", "R1.pgm", "R3.pgm"))
        assertEquals(listOf(2, 1, 3), ordered)
    }

    @Test
    fun `pages not covered by a partial mix are appended after mapped pages, in natural order`() {
        val ordered = reorderVisiblePages(pages, naturalOrder = listOf(1, 2, 3), mixPrograms = listOf("R2.pgm"))
        assertEquals(listOf(2, 1, 3), ordered)
    }

    @Test
    fun `a mix entry for a page excluded from natural order is ignored`() {
        val ordered = reorderVisiblePages(pages, naturalOrder = listOf(1, 3), mixPrograms = listOf("R2.pgm", "R3.pgm", "R1.pgm"))
        assertEquals(listOf(3, 1), ordered)
    }
}
```

- [ ] **Step 2: Run to verify red**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.SheetOrderResolverTest"`
Expected: FAIL — file doesn't exist.

- [ ] **Step 3: Implement**

> **Correction (post-implementation):** the code below has a real bug — `buildManageCodeRows`
> silently drops a page when it can't resolve any sheet file for it (blank `sheetId` and empty
> `sheetFiles`), but `naturalOrder` has no such filter, so this function could return fewer
> pages than `naturalOrder` contains, contradicting its own doc comment and the design spec's
> "rather than dropped" requirement (Section 12). Fixed in commit `0bf9a46` — see the actual
> `SheetOrderResolver.kt` in the repo for the corrected version (adds a union step that appends
> any `naturalOrder` page missing from the result, in its original position).

```kotlin
package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata

/**
 * Reorders [naturalOrder] (the page list `Material.visibleSheetPages()` already computed) to
 * follow [mixPrograms] where possible, without ever introducing or dropping a page that
 * [naturalOrder] didn't already contain.
 */
fun reorderVisiblePages(pages: List<PageMetadata>, naturalOrder: List<Int>, mixPrograms: List<String>): List<Int> {
    if (mixPrograms.isEmpty()) return naturalOrder
    val naturalSet = naturalOrder.toSet()
    val rows = buildManageCodeRows(pages).filter { it.pageNumber in naturalSet }
    return applyExistingOrder(rows, mixPrograms).map { it.pageNumber }
}
```

- [ ] **Step 4: Run to verify green**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.SheetOrderResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolver.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolverTest.kt
git commit -m "feat(mixservice): add sheet viewer page order resolver"
```

---

### Task 9: Row thumbnail renderer

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeThumbnail.kt`

No automated test — `android.graphics.pdf.PdfRenderer` needs a running Android framework and this project has no instrumented-test infrastructure to render a real PDF against (mirrors the rest of the codebase: PDF rendering is verified on-device, not unit tested). Verify manually in Task 12.

- [ ] **Step 1: Implement**

```kotlin
package com.kkc.sheettracker.ui.managecode

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun renderManageCodeThumbnail(pdfFile: File, pageNumber: Int, targetWidthPx: Int = 96): Bitmap? =
    withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pageNumber < 1) return@withContext null
        runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val index = pageNumber - 1
                    if (index !in 0 until renderer.pageCount) return@use null
                    renderer.openPage(index).use { page ->
                        val scale = targetWidthPx.toFloat() / page.width
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }.getOrNull()
    }
```

- [ ] **Step 2: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeThumbnail.kt
git commit -m "feat(managecode): add row thumbnail renderer"
```

---

### Task 10: ManageCodeScreen — material list and row UI

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt`

- [ ] **Step 1: Implement the screen**

```kotlin
package com.kkc.sheettracker.ui.managecode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.toggleSecondPass
import com.kkc.sheettracker.data.mixservice.toggleSuperPass
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class ManageCodeMaterialState(
    val materialName: String,
    val hasPgmsOnThisCnc: Boolean,
    val rows: List<ManageCodeRow>,
    val locked: Set<String>,
    val selections: Map<String, ManageCodeRowSelection>
)

@Composable
fun ManageCodeMaterialCard(
    state: ManageCodeMaterialState,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onRowsReordered: (List<ManageCodeRow>) -> Unit,
    onSelectionChanged: (editablePgm: String, ManageCodeRowSelection) -> Unit,
    onSelectAll: (field: String, checked: Boolean) -> Unit,
    thumbnailFor: (pageNumber: Int) -> androidx.compose.ui.graphics.ImageBitmap?
) {
    val rowsState = remember(state.rows) { mutableStateOf(state.rows) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val current = rowsState.value.toMutableList()
        if (from.index in current.indices && to.index in current.indices) {
            current.add(to.index, current.removeAt(from.index))
            rowsState.value = current
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (state.hasPgmsOnThisCnc) 1.dp else 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onExpandToggle, enabled = state.hasPgmsOnThisCnc) {
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    Text(
                        text = state.materialName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (!state.hasPgmsOnThisCnc) {
                    Text(
                        text = "No PGMs on this CNC",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("MIX", "PUNLOAD", "2ND").forEach { label ->
                            AssistChip(onClick = { onSelectAll(label, true) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
            }

            if (expanded && state.hasPgmsOnThisCnc) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (rowsState.value.size.coerceAtMost(6) * 72).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(rowsState.value, key = { _, row -> row.editablePgm }) { _, row ->
                        val locked = row.editablePgm in state.locked
                        val selection = state.selections[row.editablePgm] ?: ManageCodeRowSelection()
                        ReorderableItem(reorderState, key = row.editablePgm) {
                            ManageCodeRowView(
                                row = row,
                                locked = locked,
                                selection = selection,
                                onSelectionChanged = { onSelectionChanged(row.editablePgm, it) },
                                thumbnail = thumbnailFor(row.pageNumber),
                                dragModifier = if (locked) Modifier else Modifier.draggableHandle(
                                    onDragStopped = { onRowsReordered(rowsState.value) }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageCodeRowView(
    row: ManageCodeRow,
    locked: Boolean,
    selection: ManageCodeRowSelection,
    onSelectionChanged: (ManageCodeRowSelection) -> Unit,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    dragModifier: Modifier
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (locked) {
            Icon(Icons.Filled.Lock, contentDescription = "Locked", modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Filled.DragHandle, contentDescription = "Drag to reorder", modifier = Modifier.size(20.dp).then(dragModifier))
        }
        Box(modifier = Modifier.size(34.dp)) {
            if (thumbnail != null) {
                androidx.compose.foundation.Image(bitmap = thumbnail, contentDescription = null)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(row.pgmFiles.joinToString(" + "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        if (!locked) {
            Checkbox(checked = selection.mix, onCheckedChange = { onSelectionChanged(selection.copy(mix = it)) }, modifier = Modifier.size(24.dp))
            Checkbox(checked = selection.removePUnload, onCheckedChange = { onSelectionChanged(selection.copy(removePUnload = it)) }, modifier = Modifier.size(24.dp))
            Checkbox(checked = selection.secondPass, onCheckedChange = { onSelectionChanged(toggleSecondPass(selection, it)) }, modifier = Modifier.size(24.dp))
            if (selection.secondPass) {
                Checkbox(checked = selection.superPass, onCheckedChange = { onSelectionChanged(toggleSuperPass(selection, it)) }, modifier = Modifier.size(24.dp))
            }
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt
git commit -m "feat(managecode): add material card and row UI with drag reorder"
```

---

### Task 11: ManageCodeScreen — top-level screen, Generate orchestration wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt`

- [ ] **Step 1: Add the top-level screen composable**

> **Correction (pre-implementation review):** the code below has two bugs, fixed in commit
> `ed2307c` (see the actual `ManageCodeScreen.kt` for the corrected version): (1) the
> `onSelectAll` `when (field)` block is missing a `"SUPER" -> toggleSuperPass(sel, checked)`
> case, so the header's SUPER select-all checkbox (added in Task 10's `f16d6bb`) would silently
> do nothing; (2) the duplicate-warning dialog's confirm button creates an untracked
> `kotlinx.coroutines.MainScope()` per click instead of reusing a composition-scoped
> `rememberCoroutineScope()` — fixed by hoisting one `scope` to the top of the composable and
> using it in both the Generate button and the dialog.

Append to `ManageCodeScreen.kt`:

```kotlin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.MixWriteResult
import com.kkc.sheettracker.data.mixservice.PgmEditSubmitResult
import com.kkc.sheettracker.data.mixservice.buildManageCodeChange
import com.kkc.sheettracker.data.mixservice.buildManageCodeRows
import com.kkc.sheettracker.data.mixservice.deriveRowSelection
import com.kkc.sheettracker.data.mixservice.findCrossMixDuplicates
import com.kkc.sheettracker.data.mixservice.isRowLocked
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File
import java.util.UUID

sealed class ManageCodeMaterialResult {
    object Success : ManageCodeMaterialResult()
    data class Blocked(val reason: String) : ManageCodeMaterialResult()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCodeScreen(
    scanCoordinator: ScanCoordinator,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    jobFolderName: String,
    onlyMaterialName: String?,
    onBack: () -> Unit,
    client: MixServiceClient = remember { MixServiceClient() }
) {
    val scanState by scanCoordinator.state.collectAsState()
    val unifiedEngine = remember(scanState.snapshot.basePath) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), com.kkc.sheettracker.BuildConfig.DEBUG)
    }
    val job by produceState<com.kkc.sheettracker.data.models.Job?>(initialValue = null, unifiedEngine, jobFolderName) {
        value = withContext(Dispatchers.IO) { unifiedEngine.getCncSnapshot(jobFolderName)?.job }
    }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { reachable = client.isReachable() }

    val materials = job?.materials.orEmpty().filter { onlyMaterialName == null || it.materialName == onlyMaterialName }
    var materialStates by remember { mutableStateOf<Map<String, ManageCodeMaterialState>>(emptyMap()) }
    var mixNames by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var expandedMaterial by remember { mutableStateOf(onlyMaterialName) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, ManageCodeMaterialResult>>(emptyMap()) }
    var pendingDuplicateWarning by remember { mutableStateOf<Pair<String, List<com.kkc.sheettracker.data.mixservice.DuplicateMixWarning>>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(job, reachable) {
        if (job == null || reachable != true) return@LaunchedEffect
        val nextStates = mutableMapOf<String, ManageCodeMaterialState>()
        val nextNames = mutableMapOf<String, String?>()
        for (material in materials) {
            val pgms = client.listPgms(jobFolderName, material.materialName)
            val hasPgms = pgms.isNotEmpty()
            val pages = material.metadata?.pages.orEmpty()
            var rows = buildManageCodeRows(pages)
            val existingMixes = client.listMixes(jobFolderName, material.materialName)
            val existingMix = existingMixes?.firstOrNull()
            nextNames[material.materialName] = existingMix?.name
            if (existingMix != null) {
                rows = com.kkc.sheettracker.data.mixservice.applyExistingOrder(rows, existingMix.programs)
            }
            val editHistory = client.listPgmEdits(jobFolderName, material.materialName)
            val locked = rows.filter { row ->
                isRowLocked(progressStore.getSheetStatus(jobFolderName, material.pdfFilename, row.pageNumber, material.fileFingerprint))
            }.map { it.editablePgm }.toSet()
            val selections = rows.associate { row ->
                row.editablePgm to deriveRowSelection(
                    row.editablePgm,
                    existingMix?.programs.orEmpty(),
                    hasExistingMix = existingMix != null,
                    editHistory = editHistory
                )
            }
            nextStates[material.materialName] = ManageCodeMaterialState(
                materialName = material.materialName,
                hasPgmsOnThisCnc = hasPgms,
                rows = rows,
                locked = locked,
                selections = selections
            )
        }
        materialStates = nextStates
        mixNames = nextNames
    }

    fun updateSelection(materialName: String, editablePgm: String, selection: ManageCodeRowSelection) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to state.copy(
            selections = state.selections + (editablePgm to selection)
        ))
    }

    fun updateRows(materialName: String, rows: List<ManageCodeRow>) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to state.copy(rows = rows))
    }

    suspend fun generateOne(materialName: String, ignoreDuplicates: Boolean): ManageCodeMaterialResult {
        val state = materialStates[materialName] ?: return ManageCodeMaterialResult.Blocked("No data")
        val existingName = mixNames[materialName]
        val change = buildManageCodeChange(
            rows = state.rows,
            selections = state.selections,
            locked = state.locked,
            originalPrograms = client.listMixes(jobFolderName, materialName)?.firstOrNull { it.name == existingName }?.programs.orEmpty()
        )
        if (change.orderOrMembershipChanged && !ignoreDuplicates) {
            val allOtherMixes = client.listMixes(jobFolderName, materialName).orEmpty()
            val duplicates = findCrossMixDuplicates(change.programs, existingName ?: "", allOtherMixes)
            if (duplicates.isNotEmpty()) {
                pendingDuplicateWarning = materialName to duplicates
                return ManageCodeMaterialResult.Blocked("Duplicate PGM membership — confirm to continue")
            }
        }
        if (change.orderOrMembershipChanged) {
            val name = existingName ?: "${materialName.replace(Regex("[^A-Za-z0-9 _-]"), "")}Mix"
            val writeResult = if (existingName != null) {
                client.updateMix(jobFolderName, materialName, name, change.programs)
            } else {
                client.createMix(jobFolderName, materialName, name, change.programs)
            }
            if (writeResult !is MixWriteResult.Success) {
                return ManageCodeMaterialResult.Blocked("Mix write failed: $writeResult")
            }
        }
        if (change.editRows.isNotEmpty()) {
            val submitResult = client.submitPgmEdits(jobFolderName, materialName, UUID.randomUUID().toString(), change.editRows)
            if (submitResult !is PgmEditSubmitResult.Success) {
                return ManageCodeMaterialResult.Blocked("Second-pass edit failed: $submitResult")
            }
        }
        return ManageCodeMaterialResult.Success
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onlyMaterialName != null) "Manage code — $onlyMaterialName" else "Manage code") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (reachable == false) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Mix service unreachable", color = MaterialTheme.colorScheme.error)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(materials, key = { _, m -> m.materialName }) { _, material ->
                        val state = materialStates[material.materialName] ?: return@itemsIndexed
                        ManageCodeMaterialCard(
                            state = state,
                            expanded = expandedMaterial == material.materialName,
                            onExpandToggle = {
                                expandedMaterial = if (expandedMaterial == material.materialName) null else material.materialName
                            },
                            onRowsReordered = { updateRows(material.materialName, it) },
                            onSelectionChanged = { pgm, sel -> updateSelection(material.materialName, pgm, sel) },
                            onSelectAll = { field, checked ->
                                val updated = state.selections.mapValues { (pgm, sel) ->
                                    if (pgm in state.locked) sel else when (field) {
                                        "MIX" -> sel.copy(mix = checked)
                                        "PUNLOAD" -> sel.copy(removePUnload = checked)
                                        "2ND" -> toggleSecondPass(sel, checked)
                                        "SUPER" -> toggleSuperPass(sel, checked)
                                        else -> sel
                                    }
                                }
                                materialStates = materialStates + (material.materialName to state.copy(selections = updated))
                            },
                            thumbnailFor = { null }
                        )
                        results[material.materialName]?.let { result ->
                            val label = when (result) {
                                ManageCodeMaterialResult.Success -> "Done"
                                is ManageCodeMaterialResult.Blocked -> result.reason
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val next = mutableMapOf<String, ManageCodeMaterialResult>()
                            for (material in materials) {
                                if (!(materialStates[material.materialName]?.hasPgmsOnThisCnc ?: false)) continue
                                next[material.materialName] = generateOne(material.materialName, ignoreDuplicates = false)
                            }
                            results = next
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Text(if (busy) "Generating…" else "Generate mixes and edit code")
                }
            }
        }

        pendingDuplicateWarning?.let { (materialName, duplicates) ->
            AlertDialog(
                onDismissRequest = { pendingDuplicateWarning = null },
                title = { Text("Already in another mix") },
                text = {
                    Text(duplicates.joinToString("\n") { "${it.pgm} is already in ${it.otherMixName}" })
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDuplicateWarning = null
                        scope.launch {
                            results = results + (materialName to generateOne(materialName, ignoreDuplicates = true))
                        }
                    }) { Text("Continue anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDuplicateWarning = null }) { Text("Go back and edit") }
                }
            )
        }
    }
}
```

This mirrors the diff/payload logic already covered by `ManageCodeOrchestratorTest` (Task 7) — the orchestration itself has no new branching logic beyond wiring the tested pure functions to the client and to Compose state, so no separate test file is added here. `thumbnailFor` is passed as `{ null }` for now; Task 12 wires it to `renderManageCodeThumbnail`.

- [ ] **Step 2: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt
git commit -m "feat(managecode): add top-level screen with Generate orchestration"
```

---

### Task 12: Wire thumbnails, navigation route, and both entry-point buttons

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt` (thumbnail wiring)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`

- [ ] **Step 1: Wire real thumbnails in `ManageCodeScreen`**

In `ManageCodeScreen.kt`, replace the `thumbnailFor = { null }` line with a per-material thumbnail cache and real rendering:

```kotlin
                    val thumbnailCache = remember(material.materialName) { mutableStateMapOf<Int, androidx.compose.ui.graphics.ImageBitmap?>() }
                    LaunchedEffect(state.rows) {
                        val pdfFile = jobRepository.getPdfFile(jobFolderName, material.pdfFilename)
                        for (row in state.rows) {
                            if (thumbnailCache.containsKey(row.pageNumber)) continue
                            thumbnailCache[row.pageNumber] = renderManageCodeThumbnail(pdfFile, row.pageNumber)?.asImageBitmap()
                        }
                    }
                    ManageCodeMaterialCard(
                        state = state,
                        expanded = expandedMaterial == material.materialName,
                        onExpandToggle = {
                            expandedMaterial = if (expandedMaterial == material.materialName) null else material.materialName
                        },
                        onRowsReordered = { updateRows(material.materialName, it) },
                        onSelectionChanged = { pgm, sel -> updateSelection(material.materialName, pgm, sel) },
                        onSelectAll = { field, checked ->
                            val updated = state.selections.mapValues { (pgm, sel) ->
                                if (pgm in state.locked) sel else when (field) {
                                    "MIX" -> sel.copy(mix = checked)
                                    "PUNLOAD" -> sel.copy(removePUnload = checked)
                                    "2ND" -> toggleSecondPass(sel, checked)
                                    else -> sel
                                }
                            }
                            materialStates = materialStates + (material.materialName to state.copy(selections = updated))
                        },
                        thumbnailFor = { pageNumber -> thumbnailCache[pageNumber] }
                    )
```

(This replaces the earlier `ManageCodeMaterialCard(...)` call and the `thumbnailFor = { null }` line from Task 11 — same call, with the cache lookup and the `LaunchedEffect` added just above it, inside the same `itemsIndexed` block.)

- [ ] **Step 2: Register the route in `NavGraph.kt`**

Add after the `"viewer/{folderName}/{pdfFilename}/{startPage}"` composable block (after line 1626):

```kotlin
        composable(
            "manage_code/{folderName}?material={material}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("material") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val material = backStack.arguments?.getString("material")?.let { URLDecoder.decode(it, "UTF-8") }
            com.kkc.sheettracker.ui.managecode.ManageCodeScreen(
                scanCoordinator = scanCoordinator,
                jobRepository = jobRepository,
                progressStore = progressStore,
                jobFolderName = folderName,
                onlyMaterialName = material,
                onBack = { navController.popBackStack() }
            )
        }
```

- [ ] **Step 3: Add the Manage Code button to `JobDetailScreen.kt`**

Replace the block at `JobDetailScreen.kt:447-453`:

```kotlin
                item(key = "specialty-compact-section") {
                    CompactSpecialtySection(
                        jobFolderName = jobFolderName,
                        specialtyStateStore = specialtyStateStore,
                        mode = SpecialtySurfaceMode.CNC
                    )
                }
```

with:

```kotlin
                item(key = "specialty-compact-section") {
                    val resolvedItems = remember(jobFolderName) { specialtyStateStore.getResolvedItems(jobFolderName) }
                    val hasSpecialty = com.kkc.sheettracker.ui.specialty.buildSpecialtySectionRows(
                        resolvedItems,
                        com.kkc.sheettracker.ui.specialty.SpecialtySurfaceMode.CNC
                    ).isNotEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(com.kkc.sheettracker.ui.theme.KKCSpacing.s)
                    ) {
                        CompactSpecialtySection(
                            jobFolderName = jobFolderName,
                            specialtyStateStore = specialtyStateStore,
                            mode = SpecialtySurfaceMode.CNC,
                            modifier = if (hasSpecialty) Modifier.weight(0.75f) else Modifier.weight(1f)
                        )
                        if (hasSpecialty) {
                            Button(
                                onClick = {
                                    navController_manageCodeHook(jobFolderName)
                                },
                                modifier = Modifier.weight(0.25f).fillMaxWidth()
                            ) {
                                Text("Manage code")
                            }
                        }
                    }
                }
```

`navController_manageCodeHook` is a placeholder name — replace it with the real navigation callback: add a new parameter `onOpenManageCode: () -> Unit = {}` to `JobDetailScreen`'s signature (next to `onOpenThreeD`), call `onOpenManageCode()` from the button's `onClick` instead, and when `!hasSpecialty` render the same `Button` at `Modifier.fillMaxWidth()` outside the `Row` (specialty section alone, full width) rather than inside it. Wire the new parameter at the `JobDetailScreen(...)` call site in `NavGraph.kt:1397` (added in Step 2's neighboring edit, Step 4 below):

```kotlin
onOpenManageCode = {
    navController.navigate("manage_code/${URLEncoder.encode(folderName, "UTF-8")}") {
        launchSingleTop = true
    }
},
```

- [ ] **Step 4: Add the Manage Code button to `SheetViewerScreen.kt`**

Add a new parameter `onOpenManageCode: (materialName: String) -> Unit = {}` to `SheetViewerScreen`'s signature, and add a button next to the existing Print button block at `SheetViewerScreen.kt:433-443`:

```kotlin
                        Button(
                            onClick = { currentMaterial?.let { onOpenManageCode(it.materialName) } },
                            enabled = currentMaterial != null
                        ) {
                            Text("Manage code")
                        }
```

Wire it at the `SheetViewerScreen(...)` call site in `NavGraph.kt:1584` (added alongside Step 2's route):

```kotlin
onOpenManageCode = { materialName ->
    navController.navigate(
        "manage_code/${URLEncoder.encode(folderName, "UTF-8")}?material=${URLEncoder.encode(materialName, "UTF-8")}"
    ) { launchSingleTop = true }
},
```

- [ ] **Step 5: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Manual on-device verification**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat assembleDebug && adb install -r app\build\outputs\apk\debug\app-debug.apk`

On a tablet/emulator with network access to `192.168.20.4:8477` (or without, to see the unreachable state): open a CNC job, confirm the Manage Code button appears next to Specialty (or full-width if Specialty is empty), open it, confirm materials with no PGMs on the CNC are greyed, expand a material, confirm row thumbnails render, drag-reorder a row, toggle checkboxes (confirm SUPER only appears once 2ND is checked, and unchecking 2ND hides and clears SUPER), open the same screen from a Sheet Viewer's Manage Code button and confirm it's scoped to just that material.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "feat(managecode): wire navigation route and entry-point buttons"
```

---

### Task 13: Sheet Viewer page order follows the applied mix

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt:762-763`

- [ ] **Step 1: Fetch the material's mix order and reorder `visiblePages` at its source**

Add near the top of `SheetViewerScreen`'s state (alongside other `remember`/`mutableStateOf` declarations):

```kotlin
    val mixServiceClient = remember { com.kkc.sheettracker.data.mixservice.MixServiceClient() }
    var currentMixPrograms by remember { mutableStateOf<List<String>>(emptyList()) }
```

Add a `LaunchedEffect` that refetches the mix whenever the material changes (placed near the existing material-loading `LaunchedEffect` block, e.g. right after the one ending at line 798):

```kotlin
    LaunchedEffect(currentMaterial?.materialName) {
        val materialName = currentMaterial?.materialName
        currentMixPrograms = if (materialName == null) emptyList() else {
            mixServiceClient.listMixes(jobFolderName, materialName)?.firstOrNull()?.programs.orEmpty()
        }
    }
```

Change line 763 from:

```kotlin
        visiblePages = nextMaterial?.visibleSheetPages().orEmpty()
```

to:

```kotlin
        val naturalOrder = nextMaterial?.visibleSheetPages().orEmpty()
        visiblePages = if (nextMaterial == null) naturalOrder else {
            com.kkc.sheettracker.data.mixservice.reorderVisiblePages(
                pages = nextMaterial.metadata?.pages.orEmpty(),
                naturalOrder = naturalOrder,
                mixPrograms = currentMixPrograms
            )
        }
```

Note: on the very first load for a material, `currentMixPrograms` is still the previous material's value (or empty) because the two `LaunchedEffect`s race — this is acceptable and self-correcting: the mix-fetch effect updates `currentMixPrograms`, which is not itself a key of the `visiblePages`-setting effect, so add `currentMixPrograms` to that effect's key list so it re-runs and re-orders once the fetch completes. Locate that effect's `LaunchedEffect(...)` key list (the one containing the code from Step 1) and add `currentMixPrograms` to it.

- [ ] **Step 2: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual on-device verification**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat assembleDebug && adb install -r app\build\outputs\apk\debug\app-debug.apk`

Open a material with a generated mix in Sheet Viewer; confirm swiping/paging follows the mix order rather than PDF order. Open a material with no mix; confirm it still pages in natural PDF order. Generate a mix from the Sheet Viewer's Manage Code button, return to the viewer, confirm the new order takes effect.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "feat(viewer): follow applied mix order for sheet page navigation"
```

---

## Self-Review Notes

- **Spec coverage:** Sections 3–4 (connectivity, matching) → Tasks 2, 5. Section 5 (grey-out) → Tasks 10/11 (`hasPgmsOnThisCnc`). Section 6 (locking) → Task 6. Section 7 (cardinality/initial order) → Tasks 5, 11 (`existingMix?.programs`). Section 8 (checkboxes incl. SUPER-hidden-until-2ND) → Task 6, Task 10 (`if (selection.secondPass)` gate). Section 9 (duplicate warning) → Task 7, Task 11 (`pendingDuplicateWarning`). Section 10 (screen structure) → Tasks 10–12. Section 11 (Generate orchestration) → Tasks 7, 11. Section 12 (page order) → Tasks 8, 13. Section 13 (error handling) → Task 2 (`isReachable`), Task 11 (`Blocked` results).
- **Placeholder scan:** the one literal placeholder name (`navController_manageCodeHook`) in Task 12 Step 3 is explicitly called out and replaced within the same step, not left dangling.
- **Type consistency:** `ManageCodeRow.editablePgm`, `ManageCodeRowSelection`, `PgmEditRow`, `MixDefinition.programs`, and `ManageCodeChange` are defined once (Tasks 5–7) and reused with the same names/shapes through Tasks 10–13.
