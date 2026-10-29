package com.iwaradownloader.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android replacement for gallery-dl's image mode for Iwara image posts (iwara.tv/image/<id>).
 * Uses the Iwara API: GET /image/<id> lists the files, originals live on i.iwara.tv.
 * Public posts need no login; with an account the API access token is sent as a Bearer header.
 */
class IwaraImages {

    data class ImageFile(val url: String, val ext: String)
    data class Post(val title: String, val uploader: String, val files: List<ImageFile>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0"

    // Iwara moved its API between hosts during 2026, so both are tried in order.
    private val apiRoots = listOf("https://api.iwara.tv", "https://apiq.iwara.tv")
    private val fileRoot = "https://i.iwara.tv"

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
