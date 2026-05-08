package com.kkc.sheettracker.tools.migration

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.system.exitProcess

private const val SCHEMA_VERSION = "migration_v1"
private const val CABINET_SKIP_MARKER = "|@cab:"

fun main(args: Array<String>) {
    val options = try {
        CliOptions.parse(args)
    } catch (t: Throwable) {
        System.err.println("Error: ${t.message}")
        System.err.println(CliOptions.usage())
        exitProcess(2)
    }

    val runner = MigrationRunner(options)
    val summary = runner.run()
    val gson = GsonBuilder().setPrettyPrinting().create()
    println(gson.toJson(summary))

    if (summary.jobsFailed > 0 || summary.modeResults.values.any { it.failed > 0 }) {
        exitProcess(1)
    }
}

private data class CliOptions(
    val basePath: Path,
    val maxEvents: Int,
    val dryRun: Boolean,
    val jobs: Set<String>?,
    val force: Boolean,
    val writeMarker: Boolean
) {
    companion object {
        fun parse(args: Array<String>): CliOptions {
            var basePath: String? = null
            var maxEvents = 300
            var dryRun = false
            var jobs: Set<String>? = null
            var force = false
            var writeMarker = false

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--base-path" -> {
                        val value = args.getOrNull(i + 1) ?: error("--base-path requires a value")
                        basePath = value
                        i += 2
                    }
                    "--max-events" -> {
                        val raw = args.getOrNull(i + 1) ?: error("--max-events requires a value")
                        maxEvents = raw.toIntOrNull()?.takeIf { it > 0 } ?: error("--max-events must be a positive integer")
                        i += 2
                    }
                    "--dry-run" -> {
                        dryRun = true
                        i += 1
                    }
                    "--jobs" -> {
                        val value = args.getOrNull(i + 1) ?: error("--jobs requires a comma-separated value")
                        jobs = value
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toSet()
                            .takeIf { it.isNotEmpty() }
                        i += 2
                    }
                    "--force" -> {
                        force = true
                        i += 1
                    }
                    "--write-marker" -> {
                        writeMarker = true
                        i += 1
                    }
                    else -> error("Unknown argument: $arg")
                }
            }

            val resolvedBase = basePath ?: error("--base-path is required")
            val base = Path.of(resolvedBase)
            require(base.exists() && base.isDirectory()) {
                "--base-path must be an existing directory: ${base.absolutePathString()}"
            }

            return CliOptions(
                basePath = base,
                maxEvents = maxEvents,
                dryRun = dryRun,
                jobs = jobs,
                force = force,
                writeMarker = writeMarker
            )
        }

        fun usage(): String = """
            Usage:
              ./gradlew :tools-migration:run --args="--base-path 'D:/Sync/Ready Jobs' [--max-events 300] [--dry-run] [--jobs 'JobA,JobB'] [--force] [--write-marker]"
        """.trimIndent()
    }
}

private class MigrationRunner(
    private val options: CliOptions
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val startedAt: Instant = Instant.now()
    private val backupStamp: String = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(ZoneOffset.UTC)
        .format(startedAt)
    private val discoveredTabletIds = linkedSetOf<String>()

    fun run(): MigrationSummary {
        val selectedJobDirs = resolveJobs()
        val failures = mutableListOf<MigrationFailure>()
        val modeTallies = mutableMapOf(
            Mode.CNC to ModeTally(),
            Mode.HARDWOODS to ModeTally()
        )
        var jobsSucceeded = 0
        var jobsFailed = 0

        for ((jobName, jobDir) in selectedJobDirs) {
            val modeResults = listOf(
                migrateCnc(jobName, jobDir),
                migrateHardwoods(jobName, jobDir)
            )

            modeResults.forEach { modeResult ->
                val tally = modeTallies.getValue(modeResult.mode)
                if (modeResult.status == "success" || modeResult.status == "skipped_existing") {
                    tally.succeeded += 1
                } else {
                    tally.failed += 1
                    failures += MigrationFailure(
                        jobFolder = jobName,
                        mode = modeResult.mode.name,
                        reason = modeResult.errors.firstOrNull() ?: "Unknown failure"
                    )
                }
            }

            val jobPass = modeResults.all { it.status == "success" || it.status == "skipped_existing" }
            if (jobPass) {
                jobsSucceeded += 1
            } else {
                jobsFailed += 1
            }
        }

        for (missingJob in resolveMissingJobs(selectedJobDirs)) {
            jobsFailed += 1
            modeTallies.getValue(Mode.CNC).failed += 1
            modeTallies.getValue(Mode.HARDWOODS).failed += 1
            failures += MigrationFailure(
                jobFolder = missingJob,
                mode = "BOTH",
                reason = "Job folder not found under base path"
            )
        }

        val completedAt = Instant.now()
        val summary = MigrationSummary(
            schemaVersion = SCHEMA_VERSION,
            basePath = options.basePath.absolutePathString(),
            startedAt = startedAt.toString(),
            completedAt = completedAt.toString(),
            maxEvents = options.maxEvents,
            jobsScanned = selectedJobDirs.size + resolveMissingJobs(selectedJobDirs).size,
            jobsSucceeded = jobsSucceeded,
            jobsFailed = jobsFailed,
            modeResults = modeTallies.mapValues { (_, v) ->
                ModeSummary(
                    succeeded = v.succeeded,
                    failed = v.failed
                )
            },
            failures = failures
        )

        if (!options.dryRun) {
            writeGlobalArtifacts(summary)
        }

        return summary
    }

    private fun resolveJobs(): List<Pair<String, Path>> {
        val discovered = Files.list(options.basePath).use { stream ->
            stream
                .filter { it.isDirectory() }
                .filter { it.name != ".appupdates" }
                .map { it.name to it }
                .toList()
        }
        val filter = options.jobs ?: return discovered.sortedBy { it.first }
        return discovered
            .filter { it.first in filter }
            .sortedBy { it.first }
    }

    private fun resolveMissingJobs(resolved: List<Pair<String, Path>>): List<String> {
        val filter = options.jobs ?: return emptyList()
        val found = resolved.map { it.first }.toSet()
        return filter.filterNot { it in found }.sorted()
    }

    private fun migrateCnc(jobName: String, jobDir: Path): ModeResult {
        val mode = Mode.CNC
        val trackerDir = jobDir.resolve("CNC").resolve(".tracker")
        val markerPath = trackerDir.resolve(".migration_v1.json")

        if (!options.force && markerPath.exists()) {
            return ModeResult(
                mode = mode,
                status = "skipped_existing",
                sourceFiles = emptyList(),
                sourceActionCount = 0,
                migratedActionCount = 0,
                parity = ParitySummary(passed = true, detail = "Existing marker present"),
                errors = emptyList()
            )
        }

        val errors = mutableListOf<String>()
        val sourceFiles = listTrackerJsonFiles(trackerDir)
        val sourceProgress = sourceFiles.mapNotNull { file ->
            parseCncTabletProgress(file, errors)
        }
        sourceProgress.forEach { discoveredTabletIds += it.tabletId }

        val actions = sourceProgress
            .flatMap { progress ->
                progress.actions.mapIndexed { index, action ->
                    CncActionRecord(
                        tabletId = progress.tabletId,
                        sourceFile = progress.sourceFile,
                        sourceIndex = index,
                        action = action
                    )
                }
            }
            .sortedWith(compareBy<CncActionRecord> { it.action.timestamp }.thenBy { it.sourceFile }.thenBy { it.sourceIndex })

        val eventsByTablet = mutableMapOf<String, MutableList<MigrationEvent>>()
        val lamportByTablet = mutableMapOf<String, Int>()

        actions.forEachIndexed { globalIndex, record ->
            val mapped = mapCncEvent(
                jobName = jobName,
                mode = mode,
                globalOrder = globalIndex + 1,
                lamport = (lamportByTablet[record.tabletId] ?: 0) + 1,
                record = record
            )
            if (mapped == null) {
                errors += "Unknown CNC action '${record.action.action}' in ${record.sourceFile}"
            } else {
                lamportByTablet[record.tabletId] = mapped.lamport
                eventsByTablet.getOrPut(record.tabletId) { mutableListOf() } += mapped
            }
        }

        val compactedByTablet = eventsByTablet.mapValues { (_, events) ->
            events.sortedBy { it.migrationOrder }.takeLast(options.maxEvents)
        }
        val migratedEvents = compactedByTablet.values.flatten().sortedBy { it.migrationOrder }

        val legacyState = replayLegacyCnc(actions.map { it.action })
        val migratedState = replayMigratedCnc(migratedEvents)
        val parity = compareCncStates(legacyState, migratedState)

        val status = if (parity.passed && errors.isEmpty()) "success" else "failed"
        if (!parity.passed) {
            errors += parity.detail
        }

        if (!options.dryRun) {
            try {
                trackerDir.toFile().mkdirs()
                backupModeSources(
                    backupRoot = trackerDir.resolve(".backup_migration_v1_$backupStamp"),
                    sources = sourceFiles,
                    backupPrefix = "tracker"
                )
                writeEventStreams(trackerDir, compactedByTablet)
                writeModeMarker(
                    markerPath = markerPath,
                    marker = buildMarker(
                        jobName = jobName,
                        mode = mode,
                        sourceFiles = sourceFiles,
                        sourceActionCount = actions.size,
                        migratedActionCount = migratedEvents.size,
                        status = status,
                        parity = parity,
                        errors = errors
                    )
                )
            } catch (t: Throwable) {
                return ModeResult(
                    mode = mode,
                    status = "failed",
                    sourceFiles = sourceFiles.map { it.absolutePathString() },
                    sourceActionCount = actions.size,
                    migratedActionCount = migratedEvents.size,
                    parity = parity,
                    errors = errors + "Write failure: ${t.message}"
                )
            }
        }

        return ModeResult(
            mode = mode,
            status = status,
            sourceFiles = sourceFiles.map { it.absolutePathString() },
            sourceActionCount = actions.size,
            migratedActionCount = migratedEvents.size,
            parity = parity,
            errors = errors
        )
    }

    private fun migrateHardwoods(jobName: String, jobDir: Path): ModeResult {
        val mode = Mode.HARDWOODS
        val trackerDir = jobDir.resolve(".metadata").resolve("hardwoods").resolve(".tracker")
        val legacyTrackerDir = jobDir.resolve("Hardwoods").resolve(".tracker")
        val markerPath = trackerDir.resolve(".migration_v1.json")

        if (!options.force && markerPath.exists()) {
            return ModeResult(
                mode = mode,
                status = "skipped_existing",
                sourceFiles = emptyList(),
                sourceActionCount = 0,
                migratedActionCount = 0,
                parity = ParitySummary(passed = true, detail = "Existing marker present"),
                errors = emptyList()
            )
        }

        val errors = mutableListOf<String>()
        val canonicalFiles = listTrackerJsonFiles(trackerDir)
        val legacyFiles = listTrackerJsonFiles(legacyTrackerDir)
        val allSourceFiles = (canonicalFiles + legacyFiles).distinct()

        val candidates = allSourceFiles.mapNotNull { file ->
            parseHardwoodsTabletProgress(file, errors)
        }

        val chosenByTablet = mutableMapOf<String, HardwoodsTabletProgress>()
        for (candidate in candidates) {
            discoveredTabletIds += candidate.tabletId
            val existing = chosenByTablet[candidate.tabletId]
            if (existing == null || preferHardwoods(candidate, existing)) {
                chosenByTablet[candidate.tabletId] = candidate
            }
        }

        val actions = chosenByTablet.values
            .flatMap { progress ->
                progress.actions.mapIndexed { index, action ->
                    HardwoodsActionRecord(
                        tabletId = progress.tabletId,
                        sourceFile = progress.sourceFile,
                        sourceIndex = index,
                        action = action
                    )
                }
            }
            .sortedWith(compareBy<HardwoodsActionRecord> { it.action.timestamp }.thenBy { it.sourceFile }.thenBy { it.sourceIndex })

        val eventsByTablet = mutableMapOf<String, MutableList<MigrationEvent>>()
        val lamportByTablet = mutableMapOf<String, Int>()

        actions.forEachIndexed { globalIndex, record ->
            val mapped = mapHardwoodsEvent(
                jobName = jobName,
                mode = mode,
                globalOrder = globalIndex + 1,
                lamport = (lamportByTablet[record.tabletId] ?: 0) + 1,
                record = record
            )
            if (mapped == null) {
                errors += "Unknown Hardwoods action '${record.action.action}' in ${record.sourceFile}"
            } else {
                lamportByTablet[record.tabletId] = mapped.lamport
                eventsByTablet.getOrPut(record.tabletId) { mutableListOf() } += mapped
            }
        }

        val compactedByTablet = eventsByTablet.mapValues { (_, events) ->
            events.sortedBy { it.migrationOrder }.takeLast(options.maxEvents)
        }
        val migratedEvents = compactedByTablet.values.flatten().sortedBy { it.migrationOrder }

        val legacyState = replayLegacyHardwoods(actions.map { it.action })
        val migratedState = replayMigratedHardwoods(migratedEvents)
        val parity = compareHardwoodsStates(legacyState, migratedState)

        val status = if (parity.passed && errors.isEmpty()) "success" else "failed"
        if (!parity.passed) {
            errors += parity.detail
        }

        if (!options.dryRun) {
            try {
                trackerDir.toFile().mkdirs()
                backupModeSources(
                    backupRoot = trackerDir.resolve(".backup_migration_v1_$backupStamp"),
                    sources = canonicalFiles,
                    backupPrefix = "canonical"
                )
                backupModeSources(
                    backupRoot = trackerDir.resolve(".backup_migration_v1_$backupStamp"),
                    sources = legacyFiles,
                    backupPrefix = "legacy"
                )
                writeEventStreams(trackerDir, compactedByTablet)
                writeModeMarker(
                    markerPath = markerPath,
                    marker = buildMarker(
                        jobName = jobName,
                        mode = mode,
                        sourceFiles = allSourceFiles,
                        sourceActionCount = actions.size,
                        migratedActionCount = migratedEvents.size,
                        status = status,
                        parity = parity,
                        errors = errors
                    )
                )
            } catch (t: Throwable) {
                return ModeResult(
                    mode = mode,
                    status = "failed",
                    sourceFiles = allSourceFiles.map { it.absolutePathString() },
                    sourceActionCount = actions.size,
                    migratedActionCount = migratedEvents.size,
                    parity = parity,
                    errors = errors + "Write failure: ${t.message}"
                )
            }
        }

        return ModeResult(
            mode = mode,
            status = status,
            sourceFiles = allSourceFiles.map { it.absolutePathString() },
            sourceActionCount = actions.size,
            migratedActionCount = migratedEvents.size,
            parity = parity,
            errors = errors
        )
    }

    private fun writeGlobalArtifacts(summary: MigrationSummary) {
        val appUpdatesDir = options.basePath.resolve(".appupdates")
        appUpdatesDir.toFile().mkdirs()

        discoveredTabletIds.forEach { tabletId ->
            val signalPath = appUpdatesDir.resolve(tabletId).resolve("signals.ndjson")
            if (!signalPath.exists()) {
                writeAtomic(signalPath, "")
            }
        }

        writeAtomic(
            appUpdatesDir.resolve("migration_summary.json"),
            gson.toJson(summary)
        )

        val allPass = summary.jobsFailed == 0 && summary.modeResults.values.all { it.failed == 0 }
        if (allPass && options.writeMarker) {
            val marker = CompletionMarker(
                schemaVersion = SCHEMA_VERSION,
                completedAt = Instant.now().toString(),
                jobsSucceeded = summary.jobsSucceeded,
                jobsFailed = summary.jobsFailed,
                maxEvents = summary.maxEvents
            )
            writeAtomic(
                appUpdatesDir.resolve("migration_complete.json"),
                gson.toJson(marker)
            )
        }
    }

    private fun listTrackerJsonFiles(dir: Path): List<Path> {
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.name.endsWith(".json", ignoreCase = true) }
                .filter { !it.name.startsWith(".") }
                .sorted()
                .toList()
        }
    }

    private fun parseCncTabletProgress(file: Path, errors: MutableList<String>): CncTabletProgress? {
        val root = readJsonObject(file, errors) ?: return null
        val tabletId = root.getAsString("tabletId")?.ifBlank { null } ?: file.name.removeSuffix(".json")
        val actions = root.getAsArray("actions").orEmpty().mapIndexedNotNull { index, element ->
            if (!element.isJsonObject) {
                errors += "CNC action[$index] is not an object in ${file.absolutePathString()}"
                return@mapIndexedNotNull null
            }
            val obj = element.asJsonObject
            CncLegacyAction(
                file = obj.getAsString("file").orEmpty(),
                page = obj.getAsInt("page"),
                part = obj.getAsIntOrNull("part"),
                action = obj.getAsString("action").orEmpty(),
                timestamp = obj.getAsString("timestamp").orEmpty(),
                fileFingerprint = obj.getAsString("fileFingerprint")
            )
        }
        return CncTabletProgress(tabletId = tabletId, sourceFile = file.name, actions = actions)
    }

    private fun parseHardwoodsTabletProgress(file: Path, errors: MutableList<String>): HardwoodsTabletProgress? {
        val root = readJsonObject(file, errors) ?: return null
        val tabletId = root.getAsString("tabletId")?.ifBlank { null } ?: file.name.removeSuffix(".json")
        val actions = root.getAsArray("actions").orEmpty().mapIndexedNotNull { index, element ->
            if (!element.isJsonObject) {
                errors += "Hardwoods action[$index] is not an object in ${file.absolutePathString()}"
                return@mapIndexedNotNull null
            }
            val obj = element.asJsonObject
            HardwoodsLegacyAction(
                docType = obj.getAsString("docType").orEmpty(),
                rowId = obj.getAsString("rowId").orEmpty(),
                totalsKey = obj.getAsString("totalsKey"),
                action = obj.getAsString("action").orEmpty(),
                value = obj.getAsIntOrNull("value"),
                timestamp = obj.getAsString("timestamp").orEmpty()
            )
        }
        return HardwoodsTabletProgress(tabletId = tabletId, sourceFile = file.name, actions = actions)
    }

    private fun preferHardwoods(a: HardwoodsTabletProgress, b: HardwoodsTabletProgress): Boolean {
        if (a.actions.size != b.actions.size) return a.actions.size > b.actions.size
        val aLast = a.actions.maxOfOrNull { it.timestamp }.orEmpty()
        val bLast = b.actions.maxOfOrNull { it.timestamp }.orEmpty()
        return aLast > bLast
    }

    private fun readJsonObject(file: Path, errors: MutableList<String>): JsonObject? {
        return try {
            val parsed = JsonParser.parseString(Files.readString(file))
            if (!parsed.isJsonObject) {
                errors += "Not a JSON object: ${file.absolutePathString()}"
                null
            } else {
                parsed.asJsonObject
            }
        } catch (t: Throwable) {
            errors += "Failed to parse JSON ${file.absolutePathString()}: ${t.message}"
            null
        }
    }

    private fun mapCncEvent(
        jobName: String,
        mode: Mode,
        globalOrder: Int,
        lamport: Int,
        record: CncActionRecord
    ): MigrationEvent? {
        val op = when (record.action.action) {
            "complete" -> "set_complete_true"
            "uncomplete" -> "set_complete_false"
            "skip" -> "set_skipped_true"
            "unskip" -> "set_skipped_false"
            "bad_part" -> "set_bad_part_true"
            "unbad_part" -> "set_bad_part_false"
            else -> return null
        }

        val fingerprintToken = record.action.fileFingerprint?.takeIf { it.isNotBlank() } ?: "legacy"
        val targetKey = if (record.action.part != null) {
            "cnc_part|${record.action.file}|${record.action.page}|${record.action.part}|$fingerprintToken"
        } else {
            "cnc|${record.action.file}|${record.action.page}|$fingerprintToken"
        }

        val payload = linkedMapOf<String, Any?>(
            "file" to record.action.file,
            "page" to record.action.page,
            "part" to record.action.part,
            "fileFingerprint" to record.action.fileFingerprint,
            "sourceFile" to record.sourceFile,
            "sourceIndex" to record.sourceIndex
        )

        return MigrationEvent(
            eventId = "${record.tabletId}:${mode.name}:$jobName:$globalOrder",
            tabletId = record.tabletId,
            mode = mode.name,
            jobFolder = jobName,
            targetKey = targetKey,
            op = op,
            payload = payload,
            lamport = lamport,
            wallTime = record.action.timestamp.ifBlank { Instant.EPOCH.toString() },
            source = SCHEMA_VERSION,
            migrationOrder = globalOrder
        )
    }

    private fun mapHardwoodsEvent(
        jobName: String,
        mode: Mode,
        globalOrder: Int,
        lamport: Int,
        record: HardwoodsActionRecord
    ): MigrationEvent? {
        val op = when (record.action.action) {
            "set_done_count",
            "set_bad_count",
            "set_skipped",
            "clear_skipped",
            "add_totals_rip10_done_count",
            "set_totals_rip10_done_count" -> record.action.action
            else -> return null
        }

        val totalsKey = record.action.totalsKey?.takeIf { it.isNotBlank() }
            ?: record.action.rowId.takeIf { it.isNotBlank() }
        val targetKey = if (totalsKey != null) {
            "hw_totals|$totalsKey"
        } else {
            "hw_row|${record.action.docType}|${record.action.rowId}"
        }

        val payload = linkedMapOf<String, Any?>(
            "docType" to record.action.docType,
            "rowId" to record.action.rowId,
            "totalsKey" to record.action.totalsKey,
            "value" to record.action.value,
            "sourceFile" to record.sourceFile,
            "sourceIndex" to record.sourceIndex
        )

        return MigrationEvent(
            eventId = "${record.tabletId}:${mode.name}:$jobName:$globalOrder",
            tabletId = record.tabletId,
            mode = mode.name,
            jobFolder = jobName,
            targetKey = targetKey,
            op = op,
            payload = payload,
            lamport = lamport,
            wallTime = record.action.timestamp.ifBlank { Instant.EPOCH.toString() },
            source = SCHEMA_VERSION,
            migrationOrder = globalOrder
        )
    }

    private fun replayLegacyCnc(actions: List<CncLegacyAction>): CncState {
        val sheets = mutableMapOf<CncSheetKey, MutableCncSheetState>()
        actions.forEach { action ->
            val key = CncSheetKey(action.file, action.page)
            val entry = sheets.getOrPut(key) { MutableCncSheetState() }
            val fp = action.fileFingerprint?.takeIf { it.isNotBlank() }
            when (action.action) {
                "complete", "uncomplete" -> {
                    val value = action.action == "complete"
                    if (fp == null) {
                        entry.completeLegacy = value
                    } else {
                        entry.completeHasFingerprint = true
                        entry.completeByFingerprint[fp] = value
                    }
                }
                "skip", "unskip" -> {
                    val value = action.action == "skip"
                    if (fp == null) {
                        entry.skippedLegacy = value
                    } else {
                        entry.skippedHasFingerprint = true
                        entry.skippedByFingerprint[fp] = value
                    }
                }
                "bad_part", "unbad_part" -> {
                    val part = action.part ?: return@forEach
                    val value = action.action == "bad_part"
                    if (fp == null) {
                        entry.badPartsLegacy[part] = value
                    } else {
                        entry.badPartsHasFingerprint = true
                        val partMap = entry.badPartsByFingerprint.getOrPut(fp) { mutableMapOf() }
                        partMap[part] = value
                    }
                }
            }
        }
        return CncState(
            sheets = sheets.mapValues { (_, value) -> value.freeze() }
        )
    }

    private fun replayMigratedCnc(events: List<MigrationEvent>): CncState {
        val synthesized = events.map { event ->
            val payload = event.payload
            CncLegacyAction(
                file = payload["file"]?.toString().orEmpty(),
                page = payload["page"]?.toString()?.toIntOrNull() ?: 0,
                part = payload["part"]?.toString()?.toIntOrNull(),
                action = when (event.op) {
                    "set_complete_true" -> "complete"
                    "set_complete_false" -> "uncomplete"
                    "set_skipped_true" -> "skip"
                    "set_skipped_false" -> "unskip"
                    "set_bad_part_true" -> "bad_part"
                    "set_bad_part_false" -> "unbad_part"
                    else -> ""
                },
                timestamp = event.wallTime,
                fileFingerprint = payload["fileFingerprint"]?.toString()
            )
        }
        return replayLegacyCnc(synthesized)
    }

    private fun compareCncStates(legacy: CncState, migrated: CncState): ParitySummary {
        if (legacy == migrated) {
            return ParitySummary(
                passed = true,
                detail = "CNC parity passed (${legacy.sheets.size} sheet keys)"
            )
        }
        val allKeys = (legacy.sheets.keys + migrated.sheets.keys).sortedBy { "${it.file}|${it.page}" }
        val firstMismatch = allKeys.firstOrNull { legacy.sheets[it] != migrated.sheets[it] }
        return ParitySummary(
            passed = false,
            detail = "CNC parity mismatch at ${firstMismatch?.file}:${firstMismatch?.page}"
        )
    }

    private fun replayLegacyHardwoods(actions: List<HardwoodsLegacyAction>): HardwoodsState {
        val rowProgress = mutableMapOf<Pair<String, String>, MutableHardwoodsRowProgress>()
        val skippedCabinet = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        val totals = mutableMapOf<String, Int>()

        actions.forEach { action ->
            decodeCabinetSkipRowId(action.rowId)?.let { decoded ->
                val key = action.docType to decoded.first
                val set = skippedCabinet.getOrPut(key) { mutableSetOf() }
                when (action.action) {
                    "set_skipped" -> set += decoded.second
                    "clear_skipped" -> set -= decoded.second
                }
                return@forEach
            }

            if (action.rowId.isNotBlank()) {
                val key = action.docType to action.rowId
                val current = rowProgress.getOrPut(key) { MutableHardwoodsRowProgress() }
                when (action.action) {
                    "set_done_count" -> current.doneCount = (action.value ?: 0).coerceAtLeast(0)
                    "set_bad_count" -> current.badCount = (action.value ?: 0).coerceAtLeast(0)
                    "set_skipped" -> current.skipped = true
                    "clear_skipped" -> current.skipped = false
                }
            }

            val totalsKey = action.totalsKey?.takeIf { it.isNotBlank() }
                ?: action.rowId.takeIf { it.isNotBlank() }
            if (totalsKey != null) {
                when (action.action) {
                    "add_totals_rip10_done_count" -> {
                        val delta = action.value ?: 0
                        val current = totals[totalsKey] ?: 0
                        totals[totalsKey] = (current + delta).coerceAtLeast(0)
                    }
                    "set_totals_rip10_done_count" -> {
                        totals[totalsKey] = (action.value ?: 0).coerceAtLeast(0)
                    }
                }
            }
        }

        val normalizedRows = rowProgress
            .mapValues { (_, value) -> value.freeze() }
            .filterValues { it.doneCount != 0 || it.badCount != 0 || it.skipped }
        val normalizedCabinet = skippedCabinet
            .mapValues { (_, value) -> value.toSortedSet() }
            .filterValues { it.isNotEmpty() }
        val normalizedTotals = totals.filterValues { it != 0 }.toSortedMap()

        return HardwoodsState(
            rowProgress = normalizedRows.toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }),
            skippedCabinet = normalizedCabinet.toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }),
            totals = normalizedTotals
        )
    }

    private fun replayMigratedHardwoods(events: List<MigrationEvent>): HardwoodsState {
        val synthesized = events.map { event ->
            val payload = event.payload
            HardwoodsLegacyAction(
                docType = payload["docType"]?.toString().orEmpty(),
                rowId = payload["rowId"]?.toString().orEmpty(),
                totalsKey = payload["totalsKey"]?.toString(),
                action = event.op,
                value = payload["value"]?.toString()?.toIntOrNull(),
                timestamp = event.wallTime
            )
        }
        return replayLegacyHardwoods(synthesized)
    }

    private fun compareHardwoodsStates(legacy: HardwoodsState, migrated: HardwoodsState): ParitySummary {
        if (legacy == migrated) {
            return ParitySummary(
                passed = true,
                detail = "Hardwoods parity passed (rows=${legacy.rowProgress.size}, totals=${legacy.totals.size})"
            )
        }
        val mismatchKey = (legacy.rowProgress.keys + migrated.rowProgress.keys)
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .firstOrNull { legacy.rowProgress[it] != migrated.rowProgress[it] }
        val detail = if (mismatchKey != null) {
            "Hardwoods row parity mismatch at ${mismatchKey.first}/${mismatchKey.second}"
        } else {
            "Hardwoods parity mismatch in cabinet or totals state"
        }
        return ParitySummary(
            passed = false,
            detail = detail
        )
    }

    private fun decodeCabinetSkipRowId(value: String): Pair<String, String>? {
        val idx = value.lastIndexOf(CABINET_SKIP_MARKER)
        if (idx <= 0) return null
        val rowId = value.substring(0, idx)
        val cabinet = value.substring(idx + CABINET_SKIP_MARKER.length)
        if (rowId.isBlank() || cabinet.isBlank()) return null
        return rowId to cabinet
    }

    private fun writeEventStreams(trackerDir: Path, streamsByTablet: Map<String, List<MigrationEvent>>) {
        val eventsDir = trackerDir.resolve("events")
        eventsDir.toFile().mkdirs()
        streamsByTablet.forEach { (tabletId, stream) ->
            val target = eventsDir.resolve("$tabletId.ndjson")
            val text = stream.joinToString(separator = "\n") { gson.toJson(it) } + if (stream.isNotEmpty()) "\n" else ""
            writeAtomic(target, text)
        }
    }

    private fun backupModeSources(backupRoot: Path, sources: List<Path>, backupPrefix: String) {
        if (sources.isEmpty()) return
        for (source in sources) {
            val destDir = backupRoot.resolve(backupPrefix)
            destDir.toFile().mkdirs()
            Files.copy(
                source,
                destDir.resolve(source.fileName),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
        }
    }

    private fun writeModeMarker(markerPath: Path, marker: MigrationModeMarker) {
        val text = gson.toJson(marker)
        writeAtomic(markerPath, text)
    }

    private fun buildMarker(
        jobName: String,
        mode: Mode,
        sourceFiles: List<Path>,
        sourceActionCount: Int,
        migratedActionCount: Int,
        status: String,
        parity: ParitySummary,
        errors: List<String>
    ): MigrationModeMarker {
        return MigrationModeMarker(
            schemaVersion = SCHEMA_VERSION,
            jobFolder = jobName,
            mode = mode.name,
            startedAt = startedAt.toString(),
            completedAt = Instant.now().toString(),
            status = status,
            sourceFiles = sourceFiles.map { it.absolutePathString() },
            sourceActionCount = sourceActionCount,
            migratedActionCount = migratedActionCount,
            parity = parity,
            errors = errors
        )
    }

    private fun writeAtomic(path: Path, content: String) {
        path.parent?.toFile()?.mkdirs()
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(
            tmp,
            content,
            StandardCharsets.UTF_8
        )
        try {
            Files.move(
                tmp,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp,
                path,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (io: IOException) {
            Files.move(
                tmp,
                path,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

private enum class Mode {
    CNC,
    HARDWOODS
}

private data class MigrationSummary(
    val schemaVersion: String,
    val basePath: String,
    val startedAt: String,
    val completedAt: String,
    val maxEvents: Int,
    val jobsScanned: Int,
    val jobsSucceeded: Int,
    val jobsFailed: Int,
    val modeResults: Map<Mode, ModeSummary>,
    val failures: List<MigrationFailure>
)

private data class ModeSummary(
    val succeeded: Int,
    val failed: Int
)

private data class MigrationFailure(
    val jobFolder: String,
    val mode: String,
    val reason: String
)

private data class CompletionMarker(
    val schemaVersion: String,
    val completedAt: String,
    val jobsSucceeded: Int,
    val jobsFailed: Int,
    val maxEvents: Int
)

private data class MigrationModeMarker(
    val schemaVersion: String,
    val jobFolder: String,
    val mode: String,
    val startedAt: String,
    val completedAt: String,
    val status: String,
    val sourceFiles: List<String>,
    val sourceActionCount: Int,
    val migratedActionCount: Int,
    val parity: ParitySummary,
    val errors: List<String>
)

private data class ParitySummary(
    val passed: Boolean,
    val detail: String
)

private data class ModeResult(
    val mode: Mode,
    val status: String,
    val sourceFiles: List<String>,
    val sourceActionCount: Int,
    val migratedActionCount: Int,
    val parity: ParitySummary,
    val errors: List<String>
)

private data class ModeTally(
    var succeeded: Int = 0,
    var failed: Int = 0
)

private data class CncLegacyAction(
    val file: String,
    val page: Int,
    val part: Int?,
    val action: String,
    val timestamp: String,
    val fileFingerprint: String?
)

private data class HardwoodsLegacyAction(
    val docType: String,
    val rowId: String,
    val totalsKey: String?,
    val action: String,
    val value: Int?,
    val timestamp: String
)

private data class CncTabletProgress(
    val tabletId: String,
    val sourceFile: String,
    val actions: List<CncLegacyAction>
)

private data class HardwoodsTabletProgress(
    val tabletId: String,
    val sourceFile: String,
    val actions: List<HardwoodsLegacyAction>
)

private data class CncActionRecord(
    val tabletId: String,
    val sourceFile: String,
    val sourceIndex: Int,
    val action: CncLegacyAction
)

private data class HardwoodsActionRecord(
    val tabletId: String,
    val sourceFile: String,
    val sourceIndex: Int,
    val action: HardwoodsLegacyAction
)

private data class MigrationEvent(
    val eventId: String,
    val tabletId: String,
    val mode: String,
    val jobFolder: String,
    val targetKey: String,
    val op: String,
    val payload: Map<String, Any?>,
    val lamport: Int,
    val wallTime: String,
    val source: String,
    val migrationOrder: Int
)

private data class CncSheetKey(
    val file: String,
    val page: Int
)

private data class CncState(
    val sheets: Map<CncSheetKey, CncSheetState>
)

private data class CncSheetState(
    val completeLegacy: Boolean,
    val completeHasFingerprint: Boolean,
    val completeTrueByFingerprint: Map<String, Boolean>,
    val skippedLegacy: Boolean,
    val skippedHasFingerprint: Boolean,
    val skippedTrueByFingerprint: Map<String, Boolean>,
    val badPartsHasFingerprint: Boolean,
    val badPartsLegacyTrue: Set<Int>,
    val badPartsTrueByFingerprint: Map<String, Set<Int>>
)

private class MutableCncSheetState {
    var completeLegacy: Boolean = false
    var completeHasFingerprint: Boolean = false
    val completeByFingerprint: MutableMap<String, Boolean> = mutableMapOf()
    var skippedLegacy: Boolean = false
    var skippedHasFingerprint: Boolean = false
    val skippedByFingerprint: MutableMap<String, Boolean> = mutableMapOf()
    var badPartsHasFingerprint: Boolean = false
    val badPartsLegacy: MutableMap<Int, Boolean> = mutableMapOf()
    val badPartsByFingerprint: MutableMap<String, MutableMap<Int, Boolean>> = mutableMapOf()

    fun freeze(): CncSheetState {
        return CncSheetState(
            completeLegacy = completeLegacy,
            completeHasFingerprint = completeHasFingerprint,
            completeTrueByFingerprint = completeByFingerprint.filterValues { it }.toSortedMap(),
            skippedLegacy = skippedLegacy,
            skippedHasFingerprint = skippedHasFingerprint,
            skippedTrueByFingerprint = skippedByFingerprint.filterValues { it }.toSortedMap(),
            badPartsHasFingerprint = badPartsHasFingerprint,
            badPartsLegacyTrue = badPartsLegacy.filterValues { it }.keys.toSortedSet(),
            badPartsTrueByFingerprint = badPartsByFingerprint
                .mapValues { (_, partMap) -> partMap.filterValues { it }.keys.toSortedSet() }
                .filterValues { it.isNotEmpty() }
                .toSortedMap()
        )
    }
}

private data class HardwoodsState(
    val rowProgress: Map<Pair<String, String>, HardwoodsRowProgress>,
    val skippedCabinet: Map<Pair<String, String>, Set<String>>,
    val totals: Map<String, Int>
)

private data class HardwoodsRowProgress(
    val doneCount: Int,
    val badCount: Int,
    val skipped: Boolean
)

private class MutableHardwoodsRowProgress {
    var doneCount: Int = 0
    var badCount: Int = 0
    var skipped: Boolean = false

    fun freeze(): HardwoodsRowProgress {
        return HardwoodsRowProgress(
            doneCount = doneCount,
            badCount = badCount,
            skipped = skipped
        )
    }
}

private fun JsonObject.getAsString(key: String): String? {
    val element = get(key) ?: return null
    return if (element.isJsonNull) null else runCatching { element.asString }.getOrNull()
}

private fun JsonObject.getAsInt(key: String): Int {
    return getAsIntOrNull(key) ?: 0
}

private fun JsonObject.getAsIntOrNull(key: String): Int? {
    val element = get(key) ?: return null
    if (element.isJsonNull) return null
    return runCatching {
        when {
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
            else -> element.asString.toIntOrNull()
        }
    }.getOrNull()
}

private fun JsonObject.getAsArray(key: String): JsonArray? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonArray) return null
    return element.asJsonArray
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
