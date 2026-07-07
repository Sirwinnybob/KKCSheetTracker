package com.kkc.sheettracker.viewer3d

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder

class ViewerServer(
    private val context: Context,
    private val baseDir: File
) : NanoHTTPD("127.0.0.1", 0) {

    data class StartResult(
        val port: Int,
        val error: String? = null
    )

    fun startWithRetry(maxAttempts: Int = 3, retryDelayMs: Long = 150L): StartResult {
        var lastError: String? = null
        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                start(SOCKET_READ_TIMEOUT, false)
                val port = listeningPort
                if (port <= 0) {
                    val errorText = "Server started with invalid listening port=$port"
                    lastError = errorText
                    Log.e("ViewerServer", "Start failed attempt=$attempt/$maxAttempts baseDir=${baseDir.absolutePath} error=$errorText")
                    stop()
                } else {
                    Log.d("ViewerServer", "Started on port $port, baseDir=${baseDir.absolutePath}, attempt=$attempt")
                    return StartResult(port = port)
                }
            } catch (e: Exception) {
                val errorText = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                lastError = errorText
                Log.e("ViewerServer", "Start failed attempt=$attempt/$maxAttempts baseDir=${baseDir.absolutePath} error=$errorText", e)
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        return StartResult(port = 0, error = lastError ?: "unknown startup failure")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri.startsWith("/api/job/") -> serveJobApi(uri)
            uri.startsWith("/jobs/") -> serveGlbFile(uri)
            else -> serveAsset(uri)
        }
    }

    private fun serveAsset(uri: String): Response {
        val assetPath = when {
            uri == "/" || uri.isBlank() -> "viewer/viewer.html"
            else -> "viewer$uri"
        }
        return try {
            val stream = context.assets.open(assetPath)
            newChunkedResponse(Response.Status.OK, mimeFor(assetPath), stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $uri")
        }
    }

    private fun normalizeRoomName(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .uppercase()

    /**
     * True if [candidate]'s canonical path is [root] itself or a descendant of it. Used to
     * reject request-derived paths (folder name, room name, relative GLB path) that attempt to
     * escape their intended directory via "../" segments, symlinks, or other canonicalization
     * gaps — canonicalizing only the leaf (e.g. the final GLB target) is not sufficient if an
     * earlier path segment (e.g. the room directory) was itself allowed to escape.
     */
    @VisibleForTesting
    internal fun isPathContainedIn(candidate: File, root: File): Boolean {
        val rootCanonical = root.canonicalFile
        val candidateCanonical = candidate.canonicalFile
        return candidateCanonical.path == rootCanonical.path ||
            candidateCanonical.path.startsWith(rootCanonical.path + File.separator)
    }

    private fun findRoomDir(folderName: String, room: String): File? {
        val threeDDir = File(baseDir, "$folderName/3D")
        if (!threeDDir.isDirectory || !isPathContainedIn(threeDDir, baseDir)) return null
        // Exact match first (common case, fast) — must stay within threeDDir; "room" is
        // attacker-controlled and can contain "../" to otherwise escape it.
        val exactMatch = File(threeDDir, room)
        if (exactMatch.isDirectory && isPathContainedIn(exactMatch, threeDDir)) {
            return exactMatch
        }
        // Normalized match — handles rooms with / or other chars invalid in folder names.
        // Safe by construction: only ever returns direct children from listFiles().
        val normalizedRoom = normalizeRoomName(room)
        return threeDDir.listFiles()
            ?.firstOrNull { it.isDirectory && normalizeRoomName(it.name) == normalizedRoom }
    }

    private fun serveJobApi(uri: String): Response {
        val tail = uri.removePrefix("/api/job/")
        val slashIdx = tail.indexOf('/')
        return if (slashIdx < 0) {
            val folderName = URLDecoder.decode(tail, "UTF-8")
            val rooms = scanRooms(folderName)
            val json = JSONObject().apply {
                put("success", true)
                put("rooms", JSONArray(rooms))
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        } else {
            val folderName = URLDecoder.decode(tail.substring(0, slashIdx), "UTF-8")
            val room = URLDecoder.decode(tail.substring(slashIdx + 1), "UTF-8")
            // Use the actual folder name on disk so the GLB URL resolves correctly
            val actualRoom = findRoomDir(folderName, room)?.name ?: room
            val glbUrl = "/jobs/${URLEncoder.encode(folderName, "UTF-8")}/${URLEncoder.encode(actualRoom, "UTF-8")}/3d_medium.glb"
            val json = JSONObject().apply {
                put("success", true)
                put("url", glbUrl)
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }
    }

    private fun serveGlbFile(uri: String): Response {
        // /jobs/{folderName}/{room}/<relative path inside room>
        val parts = uri.removePrefix("/jobs/").split("/")
        if (parts.size < 3) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Bad path")
        }
        val folderName = URLDecoder.decode(parts[0], "UTF-8")
        val room = URLDecoder.decode(parts[1], "UTF-8")
        val relativePath = parts.drop(2)
            .joinToString("/")
            .let { URLDecoder.decode(it, "UTF-8") }
        val roomDir = findRoomDir(folderName, room)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Room not found")

        val target = File(roomDir, relativePath)
        val targetCanonical = try {
            target.canonicalFile
        } catch (_: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid path")
        }
        if (!isPathContainedIn(targetCanonical, roomDir)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Blocked path")
        }

        return if (targetCanonical.exists() && targetCanonical.isFile) {
            newFixedLengthResponse(
                Response.Status.OK,
                mimeFor(targetCanonical.name),
                FileInputStream(targetCanonical),
                targetCanonical.length()
            )
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
    }

    private fun scanRooms(folderName: String): List<String> {
        val threeDDir = File(baseDir, "$folderName/3D")
        if (!threeDDir.isDirectory || !isPathContainedIn(threeDDir, baseDir)) return emptyList()
        return threeDDir.listFiles()
            ?.filter { it.isDirectory && File(it, "3d_medium.glb").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js")   -> "application/javascript; charset=utf-8"
        path.endsWith(".css")  -> "text/css; charset=utf-8"
        path.endsWith(".glb")  -> "model/gltf-binary"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".png")  -> "image/png"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".bmp")  -> "image/bmp"
        path.endsWith(".gif")  -> "image/gif"
        else -> MIME_PLAINTEXT
    }
}
