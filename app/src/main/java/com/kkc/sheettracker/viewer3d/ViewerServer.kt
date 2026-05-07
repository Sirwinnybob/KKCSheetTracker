package com.kkc.sheettracker.viewer3d

import android.content.Context
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
) : NanoHTTPD(0) {

    fun startAndGetPort(): Int {
        start(SOCKET_READ_TIMEOUT, false)
        return listeningPort
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
            val glbUrl = "/jobs/${URLEncoder.encode(folderName, "UTF-8")}/${URLEncoder.encode(room, "UTF-8")}/3d_medium.glb"
            val json = JSONObject().apply {
                put("success", true)
                put("url", glbUrl)
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }
    }

    private fun serveGlbFile(uri: String): Response {
        // /jobs/{folderName}/{room}/3d_medium.glb
        val parts = uri.removePrefix("/jobs/").split("/")
        if (parts.size < 3) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Bad path")
        }
        val folderName = URLDecoder.decode(parts[0], "UTF-8")
        val room = URLDecoder.decode(parts[1], "UTF-8")
        val filename = parts[2]
        val file = File(baseDir, "$folderName/3D/$room/$filename")
        return if (file.exists() && file.isFile) {
            newFixedLengthResponse(Response.Status.OK, "model/gltf-binary", FileInputStream(file), file.length())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "GLB not found")
        }
    }

    private fun scanRooms(folderName: String): List<String> {
        val threeDDir = File(baseDir, "$folderName/3D")
        if (!threeDDir.isDirectory) return emptyList()
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
        else -> MIME_PLAINTEXT
    }
}
