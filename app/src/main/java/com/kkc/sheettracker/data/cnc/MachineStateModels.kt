package com.kkc.sheettracker.data.cnc

data class MachineState(
    val asOf: String? = null,
    val reportDate: String? = null,
    val haveReport: Boolean? = null,
    val state: String? = null,
    val producing: Boolean? = null,
    val currentProgram: LoadedProgram? = null,
    val lastProgram: FinishedProgram? = null,
    /**
     * Blue SCM only: every program of the mix Maestro has loaded (it runs its own copies of them).
     * Null when the CNC couldn't read them or the service predates the field.
     */
    val maestroLoadedPrograms: List<String>? = null,
)

data class LoadedProgram(
    val name: String? = null,
    val running: Boolean? = null,
    val loadedAt: String? = null,
    val path: String? = null,
    val jobFolder: String? = null,
    val ambiguous: Boolean = false,
    val candidates: List<ProgramCandidate> = emptyList(),
)

data class ProgramCandidate(val path: String? = null, val jobFolder: String? = null)

data class FinishedProgram(
    val name: String? = null,
    val path: String? = null,
    val start: String? = null,
    val end: String? = null,
)

fun interface MachineStateSource {
    suspend fun fetch(): Result<MachineState>
}

