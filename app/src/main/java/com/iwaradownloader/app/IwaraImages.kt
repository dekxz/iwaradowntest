package com.iwaradownloader.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Talks to the Iwara API directly (OkHttp):
 *  - image posts (iwara.tv/image/<id>): GET /image/<id> lists the files, originals live on i.iwara.tv.
 *  - video posts (iwara.tv/video/<id>): GET /video/<id> gives a file-list URL, which is queried with an
 *    X-Version header; this replaces yt-dlp's Iwara extractor, whose API calls need browser
 *    impersonation (curl_cffi), which the bundled yt-dlp on Android cannot provide.
 * Public posts need no login; with an account the API access token is sent as a Bearer header.
 */
class IwaraImages {

    data class ImageFile(val url: String, val ext: String)
    data class Post(val title: String, val uploader: String, val files: List<ImageFile>)
    data class VideoFormat(val name: String, val url: String, val ext: String)
    data class VideoPost(val title: String, val uploader: String, val formats: List<VideoFormat>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0"

    // Iwara moved its API between hosts during 2026, so both are tried in order.
    private val apiRoots = listOf("https://api.iwara.tv", "https://apiq.iwara.tv")
    private val fileRoot = "https://i.iwara.tv"
    // Salt for the X-Version header of the video file list (same value yt-dlp's extractor uses).
    private val xVersionSalt = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"

    private fun JSONObject.str(key: String): String {
        val v = opt(key)
        return if (v == null || v == JSONObject.NULL) "" else v.toString()
    }

    private fun call(url: String, token: String? = null, body: JSONObject? = null, post: Boolean = false): JSONObject {
        val b = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Origin", "https://www.iwara.tv")
            .header("Referer", "https://www.iwara.tv/")
        if (token != null) b.header("Authorization", "Bearer $token")
        if (post) b.post((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())) else b.get()
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { null }
            if (!resp.isSuccessful) {
                val msg = json?.str("message").orEmpty().ifEmpty { text.take(120).replace('\n', ' ') }
                error("HTTP ${resp.code} $msg")
            }
            return json ?: error("unexpected response from Iwara")
        }
    }

    /** POST /user/login -> user token, POST /user/token -> short-lived access token. */
    private fun accessToken(root: String, email: String, password: String): String {
        val login = call(
            "$root/user/login",
            body = JSONObject().put("email", email).put("password", password),
            post = true
        )
        val userToken = login.str("token").ifEmpty { error("login failed: no token in response") }
        val access = call("$root/user/token", token = userToken, post = true)
        return access.str("accessToken").ifEmpty { error("login failed: no access token in response") }
    }

    /** Throws if the account cannot log in (used by the Log in dialog). */
    suspend fun verifyLogin(email: String, password: String) = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (root in apiRoots) {
            try { accessToken(root, email, password); return@withContext } catch (e: Exception) {
                errors += "${root.removePrefix("https://")}: ${e.message}"
            }
        }
        error(errors.joinToString("\n"))
    }

    suspend fun fetch(id: String, credentials: Pair<String, String>?): Post = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (root in apiRoots) {
            try {
                val token = credentials?.let { accessToken(root, it.first, it.second) }
                return@withContext parse(call("$root/image/$id", token))
            } catch (e: Exception) {
                errors += "${root.removePrefix("https://")}: ${e.message}"
            }
        }
        error("ERROR: could not get images.\n" + errors.joinToString("\n"))
    }

    // ---- videos ----
    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun getArray(url: String, xVersion: String): JSONArray {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Origin", "https://www.iwara.tv")
            .header("Referer", "https://www.iwara.tv/")
            .header("X-Version", xVersion)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${text.take(120).replace('\n', ' ')}")
            return try { JSONArray(text) } catch (_: Exception) { error("unexpected response while listing the video files") }
        }
    }

    /** Video info + direct file links ("Source", "540", "360", "preview"). */
    suspend fun fetchVideo(id: String, credentials: Pair<String, String>?): VideoPost = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (root in apiRoots) {
            try {
                val token = credentials?.let { accessToken(root, it.first, it.second) }
                return@withContext parseVideo(call("$root/video/$id", token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${root.removePrefix("https://")}: ${e.message}"
            }
        }
        error("ERROR: could not get the video.\n" + errors.joinToString("\n") + "\n(private videos need a login)")
    }

    private fun parseVideo(json: JSONObject): VideoPost {
        val fileUrl = json.str("fileUrl")
        if (fileUrl.isEmpty()) {
            val msg = json.str("message")
            error(
                when {
                    msg.isNotEmpty() -> "Iwara says: $msg (private videos need a login)"
                    json.str("embedUrl").isNotEmpty() -> "this video is embedded from another site and cannot be downloaded here"
                    else -> "this video is unplayable"
                }
            )
        }
        val http = fileUrl.toHttpUrlOrNull() ?: error("unexpected file URL from Iwara")
        val last = http.pathSegments.lastOrNull { it.isNotEmpty() } ?: error("unexpected file URL from Iwara")
        val expires = http.queryParameter("expires") ?: error("file URL from Iwara has no expiry")
        val files = getArray(fileUrl, sha1Hex("${last}_${expires}_$xVersionSalt"))

        val list = mutableListOf<VideoFormat>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val src = f.optJSONObject("src") ?: continue
            var u = src.str("view").ifEmpty { src.str("download") }
            if (u.isEmpty()) continue
            if (u.startsWith("//")) u = "https:$u"
            val name = f.str("name").ifEmpty { "default" }
            val ext = f.str("type").substringAfter('/', "mp4").substringBefore(';').ifEmpty { "mp4" }
            list += VideoFormat(name, u, ext)
        }
        if (list.isEmpty()) error("no downloadable video files found")
        val user = json.optJSONObject("user")
        val uploader = user?.str("name").orEmpty().ifEmpty { user?.str("username").orEmpty() }
        return VideoPost(json.str("title"), uploader, list)
    }

    private fun parse(json: JSONObject): Post {
        val files = json.optJSONArray("files")
        if (files == null || files.length() == 0) {
            val msg = json.str("message")
            error(if (msg.isNotEmpty()) "Iwara says: $msg (private posts need a login)" else "no images in this post")
        }
        val user = json.optJSONObject("user")
        val uploader = user?.str("name").orEmpty().ifEmpty { user?.str("username").orEmpty() }
        val list = mutableListOf<ImageFile>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val mime = f.str("mime")
            if (mime.isNotEmpty() && !mime.startsWith("image")) continue
            val fid = f.str("id")
            if (fid.isEmpty()) continue
            var name = f.str("name")
            if (name.isEmpty()) name = "$fid." + (mime.substringAfter('/', "jpeg").substringBefore('+').ifEmpty { "jpeg" })
            val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "jpg" }
            list += ImageFile("$fileRoot/image/original/$fid/$name", ext)
        }
        if (list.isEmpty()) error("no images in this post")
        return Post(json.str("title"), uploader, list)
    }

    suspend fun download(imageUrl: String, target: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(imageUrl)
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.iwara.tv/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Image download failed (HTTP ${resp.code}).")
            resp.body!!.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}
