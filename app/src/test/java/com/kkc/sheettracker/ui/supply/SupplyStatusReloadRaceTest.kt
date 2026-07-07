package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.data.models.SupplyItem
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the status-change write-then-reload race in SupplyDashboardScreen's
 * status picker: each status pick calls repository.setStatus() then repository.getItems() and
 * assigns the result to `items`. Two picks issued close together can have the *older* pick's
 * reload complete after the *newer* pick's, overwriting fresh state with stale data. The fix
 * (performSupplyStatusChange) guards the assignment with a monotonic request id shared across
 * calls, so only the reload that's still current when it completes is applied.
 */
class SupplyStatusReloadRaceTest {

    private fun item(name: String) = SupplyItem(
        id = "item-1",
        categoryId = "cat-1",
        name = name,
        status = "IN STOCK",
        statusBy = "tester",
        statusAt = "2026-07-07T00:00:00Z",
        notes = null,
        fields = emptyMap(),
        customFields = emptyMap(),
        attachmentIds = emptyList(),
        createdAt = "2026-07-07T00:00:00Z",
        updatedAt = "2026-07-07T00:00:00Z"
    )

    @Test
    fun `slower older reload does not clobber a faster newer reload`() = runBlocking {
        val requestIdCounter = AtomicLong(0)
        val applied = mutableListOf<List<SupplyItem>>()
        val staleResult = listOf(item("stale"))
        val freshResult = listOf(item("fresh"))

        // "older" starts first (claims request id 1) but its reload is slow.
        val older = async {
            performSupplyStatusChange(
                setStatus = {},
                reloadItems = {
                    delay(50)
                    staleResult
                },
                currentItems = { emptyList() },
                requestIdCounter = requestIdCounter,
                onItemsReloaded = { applied += it }
            )
        }
        // Give `older` time to claim its request id before `newer` starts.
        delay(10)
        // "newer" starts second (claims request id 2) and returns immediately, finishing well
        // before `older`'s delayed reload does.
        val newer = async {
            performSupplyStatusChange(
                setStatus = {},
                reloadItems = { freshResult },
                currentItems = { emptyList() },
                requestIdCounter = requestIdCounter,
                onItemsReloaded = { applied += it }
            )
        }

        awaitAll(older, newer)

        // Only the newer (still-current) reload should have been applied; the older, slower
        // reload lost the race and must be dropped instead of overwriting `applied` last.
        assertEquals(listOf(freshResult), applied)
    }

    @Test
    fun `single status change still applies its reload normally`() = runBlocking {
        val requestIdCounter = AtomicLong(0)
        var applied: List<SupplyItem>? = null
        val result = listOf(item("only"))

        performSupplyStatusChange(
            setStatus = {},
            reloadItems = { result },
            currentItems = { emptyList() },
            requestIdCounter = requestIdCounter,
            onItemsReloaded = { applied = it }
        )

        assertEquals(result, applied)
    }

    @Test
    fun `reload failure falls back to current items and still applies if still current`() = runBlocking {
        val requestIdCounter = AtomicLong(0)
        var applied: List<SupplyItem>? = null
        val fallback = listOf(item("fallback"))

        performSupplyStatusChange(
            setStatus = {},
            reloadItems = { throw RuntimeException("boom") },
            currentItems = { fallback },
            requestIdCounter = requestIdCounter,
            onItemsReloaded = { applied = it }
        )

        assertEquals(fallback, applied)
    }

    @Test
    fun `setStatus failure invokes onFailure but reload still proceeds`() = runBlocking {
        val requestIdCounter = AtomicLong(0)
        var applied: List<SupplyItem>? = null
        var failure: Throwable? = null
        val result = listOf(item("after-failed-write"))

        performSupplyStatusChange(
            setStatus = { throw RuntimeException("write failed") },
            reloadItems = { result },
            currentItems = { emptyList() },
            requestIdCounter = requestIdCounter,
            onItemsReloaded = { applied = it },
            onFailure = { failure = it }
        )

        assertEquals("write failed", failure?.message)
        assertEquals(result, applied)
    }
}
