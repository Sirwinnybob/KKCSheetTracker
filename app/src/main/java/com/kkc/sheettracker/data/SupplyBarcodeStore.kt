package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.SupplyItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ScanMode {
    object Idle : ScanMode
    object Global : ScanMode
    data class Item(val itemId: String) : ScanMode
}

// Owns `.supply/barcodes.json` — the barcode-string -> item-id lookup index — plus in-memory
// scan-session UI state (ScanMode + pickPendingBarcode) consumed by the barcode scanning screens.
//
// CROSS-PROGRAM: barcodes.json lives alongside items/<id>.json in the Syncthing-replicated
// `.supply` directory (see SupplyRepository's CROSS-PROGRAM notes / METADATA_AUDIT.md H-07).
// Writes go through the shared atomicWriteFile() helper so a concurrent reader (peer tablet,
// this app's own lookup()) never observes a torn file. Sync-conflict variants
// (`barcodes.sync-conflict-*.json`) must never be misread as the real index — only the exact
// filename `barcodes.json` is authoritative.
class SupplyBarcodeStore(
    private val basePath: String,
    private val repository: SupplyRepository
) {
    private val gson = Gson()
    private val supplyDir get() = File(basePath, ".supply")
    private val barcodesFile get() = File(supplyDir, "barcodes.json")

    // Guards the barcodes.json whole-map read-modify-write (linkSync/unlinkSync) against
    // same-process races, matching SupplyRepository's categoryWriteLock pattern. link()/unlink()
    // dispatch onto Dispatchers.IO (a real thread pool), so two near-simultaneous calls without
    // this lock could race between readIndex() and atomicWriteFile() and silently lose an update.
    private val barcodeWriteLock = Any()

    private val _scanMode = MutableStateFlow<ScanMode>(ScanMode.Idle)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _pickPendingBarcode = MutableStateFlow<String?>(null)
    val pickPendingBarcode: StateFlow<String?> = _pickPendingBarcode.asStateFlow()

    fun setScanMode(mode: ScanMode) { _scanMode.value = mode }
    fun setPickPendingBarcode(barcode: String?) { _pickPendingBarcode.value = barcode }
    fun clearPickMode() {
        _pickPendingBarcode.value = null
        _scanMode.value = ScanMode.Idle
    }

    private fun readIndex(): Map<String, String> {
        if (!barcodesFile.exists()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, String>>(
                barcodesFile.readText(),
                object : TypeToken<Map<String, String>>() {}.type
            )
        }.getOrDefault(emptyMap())
    }

    fun lookup(barcode: String): String? = readIndex()[barcode]

    fun resolveItemSync(barcode: String): SupplyItem? {
        val itemId = lookup(barcode) ?: return null
        return repository.getItem(itemId)
    }

    fun linkSync(barcode: String, itemId: String) {
        supplyDir.mkdirs()
        synchronized(barcodeWriteLock) {
            val current = readIndex().toMutableMap()
            current[barcode] = itemId
            atomicWriteFile(barcodesFile, gson.toJson(current))
        }
        val item = repository.getItem(itemId) ?: return
        val updated = (item.barcodes + barcode).distinct()
        repository.updateItemBarcodes(itemId, updated)
    }

    fun unlinkSync(barcode: String) {
        val itemId = synchronized(barcodeWriteLock) {
            val current = readIndex().toMutableMap()
            val removed = current.remove(barcode) ?: return
            atomicWriteFile(barcodesFile, gson.toJson(current))
            removed
        }
        val item = repository.getItem(itemId) ?: return
        repository.updateItemBarcodes(itemId, item.barcodes.filter { it != barcode })
    }

    suspend fun link(barcode: String, itemId: String) =
        withContext(Dispatchers.IO) { linkSync(barcode, itemId) }

    suspend fun unlink(barcode: String) =
        withContext(Dispatchers.IO) { unlinkSync(barcode) }

    suspend fun resolveItem(barcode: String): SupplyItem? =
        withContext(Dispatchers.IO) { resolveItemSync(barcode) }
}
