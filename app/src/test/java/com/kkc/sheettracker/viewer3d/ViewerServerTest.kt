package com.kkc.sheettracker.viewer3d

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

/**
 * Regression coverage for the security-review findings against ViewerServer:
 *  - #31:  server must bind to loopback only, not the wildcard address.
 *  - #31b: request-derived folder/room/relative-path segments must not be able to escape
 *          baseDir (or the resolved room dir) via "../" traversal.
 */
class ViewerServerTest {

    private lateinit var root: File
    private lateinit var baseDir: File
    private lateinit var outsideDir: File
    private lateinit var server: ViewerServer

    @Before
    fun setUp() {
        root = Files.createTempDirectory("viewerServerTest").toFile()
        baseDir = File(root, "base").apply { mkdirs() }
        outsideDir = File(root, "outside").apply { mkdirs() }

        // base/JOB1/3D/ROOM_A/3d_medium.glb
        val roomDir = File(baseDir, "JOB1/3D/ROOM_A").apply { mkdirs() }
        File(roomDir, "3d_medium.glb").writeText("GLBDATA")

        // sibling-of-base directory containing a "secret" file that must never be reachable
        File(outsideDir, "secret.txt").writeText("TOP-SECRET")

        // decoy room outside baseDir — must never show up in a rooms listing reached via a
        // traversed folderName ("../outside"); its presence is what makes the folder-name
        // traversal test meaningful (it would fail closed even without the fix if this decoy
        // didn't exist, since "outside/3D" wouldn't exist at all)
        val decoyRoom = File(outsideDir, "3D/SECRET_ROOM").apply { mkdirs() }
        File(decoyRoom, "3d_medium.glb").writeText("SECRETGLB")

        val context = mock<Context>()
        server = ViewerServer(context, baseDir)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---- #31: loopback binding ----------------------------------------------------------

    @Test
    fun bindsToLoopbackHostname_notWildcard() {
        assertEquals("127.0.0.1", server.hostname)
    }

    // ---- #31b: isPathContainedIn helper --------------------------------------------------

    @Test
    fun isPathContainedIn_acceptsRootItself() {
        assertTrue(server.isPathContainedIn(baseDir, baseDir))
    }

    @Test
    fun isPathContainedIn_acceptsDescendant() {
        val child = File(baseDir, "JOB1/3D/ROOM_A")
        assertTrue(server.isPathContainedIn(child, baseDir))
    }

    @Test
    fun isPathContainedIn_rejectsTraversalEscape() {
        val escaped = File(baseDir, "../outside")
        assertFalse(server.isPathContainedIn(escaped, baseDir))
    }

    @Test
    fun isPathContainedIn_rejectsSiblingWithPrefixedName() {
        // "baseEvil" starts with "base" as a raw string, but is not a path descendant of it —
        // a naive `path.startsWith(root.path)` (no separator) would wrongly accept this.
        val siblingWithPrefixedName = File(root, "baseEvil").apply { mkdirs() }
        assertFalse(server.isPathContainedIn(siblingWithPrefixedName, baseDir))
    }

    // ---- #31b: end-to-end traversal via serve() -------------------------------------------

    @Test
    fun serve_legitimateGlbRequest_succeeds() {
        val response = server.serve(sessionWithUri("/jobs/JOB1/ROOM_A/3d_medium.glb"))
        assertEquals(200, response.status.requestStatus)
        assertEquals("GLBDATA", response.data.bufferedReader().readText())
    }

    @Test
    fun serve_roomTraversalEscapingBaseDir_isBlocked() {
        // room segment is "../../../outside" (percent-encoded so it survives as ONE path
        // segment); before the fix, findRoomDir's exact-match branch returned this escaped
        // directory without validating containment, allowing outsideDir/secret.txt to be
        // served as if it were a legitimate room directory.
        val evilRoom = java.net.URLEncoder.encode("../../../outside", "UTF-8")
        val response = server.serve(sessionWithUri("/jobs/JOB1/$evilRoom/secret.txt"))

        assertEquals(404, response.status.requestStatus) // "Room not found" — traversal rejected
    }

    @Test
    fun serve_relativePathTraversalEscapingRoomDir_isBlocked() {
        val response = server.serve(
            sessionWithUri("/jobs/JOB1/ROOM_A/../../../../outside/secret.txt")
        )
        assertEquals(403, response.status.requestStatus) // "Blocked path"
    }

    @Test
    fun serve_folderNameTraversalEscapingBaseDir_isBlocked() {
        // folderName segment is "../outside" (percent-encoded so it survives as ONE path
        // segment). Pre-fix, findRoomDir's threeDDir (= baseDir/../outside/3D, which escapes
        // baseDir entirely) was never checked for containment, so the decoy room + GLB planted
        // under outsideDir/3D/SECRET_ROOM in setUp() would have been served successfully.
        //
        // Uses the /jobs/ route (serveGlbFile), not /api/job/, because serveJobApi's JSON
        // response building goes through org.json.JSONObject, which is stubbed to no-ops /
        // nulls under this project's `isReturnDefaultValues = true` JVM unit-test config and
        // is therefore not reliably assertable here.
        val evilFolder = java.net.URLEncoder.encode("../outside", "UTF-8")
        val response = server.serve(sessionWithUri("/jobs/$evilFolder/SECRET_ROOM/3d_medium.glb"))

        assertEquals(404, response.status.requestStatus) // "Room not found" — traversal rejected
    }

    private fun sessionWithUri(uri: String): NanoHTTPD.IHTTPSession {
        val session = mock<NanoHTTPD.IHTTPSession>()
        whenever(session.uri).thenReturn(uri)
        return session
    }
}
