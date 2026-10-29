package com.iwaradownloader.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Fmt(val label: String) {
        MP4("MP4 (Video)"), GIF("GIF (Animation)"), MP3("MP3 (Audio)"), IMAGE("Image (Original)")
    }

    data class Quality(val label: String, val id: String)
    data class Msg(val title: String, val text: String)

    private val ctx = app
    val account = AccountStore(app)
    val history = DownloadHistory(app)
    private val images = IwaraImages()

    // ---- UI state ----
    val url = MutableStateFlow("")
    val useAccount = MutableStateFlow(account.hasAccount())
    val accountStatus = MutableStateFlow(account.statusText())
    val accountLoggedIn = MutableStateFlow(account.hasAccount())
    val format = MutableStateFlow(Fmt.MP4)
    val qualities = MutableStateFlow(listOf(Quality("Best", "best")))
    val qualityIndex = MutableStateFlow(0)
    val qualityEnabled = MutableStateFlow(false)
    val logText = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val downloading = MutableStateFlow(false)
    val message = MutableStateFlow<Msg?>(null)
    val historyLabel = MutableStateFlow(history.label())
    val historyPicked = MutableStateFlow(history.isPicked())

    private var job: Job? = null
    @Volatile private var cancelled = false
    private val pid = "iwdl"

    // ---- helpers ----
    private fun log(s: String) {
        logText.update { old ->
            val n = old + s + "\n"
            if (n.length > 80_000) n.takeLast(60_000) else n
        }
    }

    private fun show(title: String, text: String) { message.value = Msg(title, text) }
    fun dismissMessage() { message.value = null }

    /** Also switches the format so it fits the link: image posts -> Image, video posts -> not Image. */
    fun setUrl(s: String) {
        url.value = s
        when (Utils.parse(s)?.kind) {
            Utils.Kind.IMAGE -> if (format.value != Fmt.IMAGE) format.value = Fmt.IMAGE
            Utils.Kind.VIDEO -> if (format.value == Fmt.IMAGE) format.value = Fmt.MP4
            null -> Unit
        }
    }
    fun setFormat(f: Fmt) { format.value = f }
    fun setUseAccount(b: Boolean) { useAccount.value = b }

    // ---- history file ----
    private fun refreshHistoryInfo() {
        historyLabel.value = history.label()
        historyPicked.value = history.isPicked()
    }

    /** "Pick existing history file": use a file chosen with the system file picker. */
    fun useHistoryFile(uri: Uri) {
        if (busy.value) { show("Please wait", "Finish or cancel the current operation first."); return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { history.usePickedFile(uri) }
            refreshHistoryInfo()
            show(if (result.ok) "History file" else "Could not use that file", result.message)
        }
    }

    fun useDefaultHistory() {
        if (busy.value) { show("Please wait", "Finish or cancel the current operation first."); return }
        history.useDefault()
        refreshHistoryInfo()
        show("History file", "Using the app's own ${DownloadHistory.NAME} in Downloads/${MediaExporter.FOLDER} again.")
    }

    private fun refreshAccountInfo() {
        accountStatus.value = account.statusText()
        accountLoggedIn.value = account.hasAccount()
    }

    /** Saves the Iwara login and, when possible, checks it against the Iwara API. */
    fun login(email: String, password: String) {
        if (busy.value) { show("Please wait", "Finish or cancel the current operation first."); return }
        account.save(email, password)
        useAccount.value = true
        refreshAccountInfo()
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try { images.verifyLogin(email, password); null } catch (e: Exception) { e.message ?: "unknown error" }
            }
            if (error == null) show("Iwara account", "Logged in successfully.")
            else show(
                "Iwara account",
                "The login was saved, but it could not be verified right now:\n$error\n\n" +
                    "If downloads of private items fail, check the email and password."
            )
        }
    }

    fun logout() {
        account.clear()
        useAccount.value = false
        refreshAccountInfo()
    }

    private fun launchJob(block: suspend () -> Unit) {
        busy.value = true
        job = viewModelScope.launch {
            try {
                val err = App.ready.await()
                if (err != null) { show("Error", err); return@launch }
                block()
            } finally {
                busy.value = false
            }
        }
    }

    private fun accountOk(): Boolean {
        if (useAccount.value && !account.hasAccount()) {
            show("Iwara account", "Log in first, or turn off 'Use Iwara account'.")
            return false
        }
        return true
    }

    /** Parses the URL and checks that it fits the chosen format. Shows a message and returns null if not. */
    private fun checkLink(u: String, fmt: Fmt): Utils.IwaraLink? {
        val link = Utils.parse(u)
        if (link == null) {
            show("Invalid URL", "Please enter an Iwara video or image URL\n(iwara.tv/video/... or iwara.tv/image/...).")
            return null
        }
        if (fmt == Fmt.IMAGE && link.kind != Utils.Kind.IMAGE) {
            show("Video link", "This is a video. Choose MP4, GIF or MP3 as the download format.")
            return null
        }
        if (fmt != Fmt.IMAGE && link.kind == Utils.Kind.IMAGE) {
            show("Image post", "This is an image post. Choose \"Image (Original)\" as the download format.")
            return null
        }
        return link
    }

    private suspend fun ytdlp(target: String, configure: YoutubeDLRequest.() -> Unit): String =
        withContext(Dispatchers.IO) {
            val req = YoutubeDLRequest(target)
            req.configure()
            if (useAccount.value && account.hasAccount()) {
                // Iwara logs in through its API; yt-dlp's Iwara extractor reads these two options.
                req.addOption("--username", account.email)
                req.addOption("--password", account.password)
            }
            val response = YoutubeDL.getInstance().execute(req, pid) { progress, _, line ->
                // Skip per-percent spam, keep the meaningful lines.
                if (line.isNotBlank() && !(line.contains("ETA") && !line.contains("100%"))) log(line.trim())
            }
            response.out
        }

    // ---- fetch formats ----
    fun fetchFormats() {
        val u = url.value.trim()
        if (u.isEmpty()) { show("Input Required", "Please enter an Iwara video URL."); return }
        val link = Utils.parse(u)
        if (link == null) { checkLink(u, Fmt.MP4); return }
        if (link.kind != Utils.Kind.VIDEO) {
            show("Image post", "Image posts have no video formats. Choose \"Image (Original)\" and tap Download.")
            return
        }
        if (busy.value || !accountOk()) return
        cancelled = false
        downloading.value = false
        logText.value = ""
        qualityEnabled.value = false
        qualities.value = listOf(Quality("Fetching...", "best"))
        qualityIndex.value = 0
        launchJob {
            try {
                val out = ytdlp(link.canonical) { addOption("--list-formats") }
                val parsed = parseFormats(out)
                if (parsed.isEmpty()) log("\nCould not parse specific formats, will use 'best'.")
                qualities.value = listOf(Quality("Best", "best")) + parsed.reversed() // best first
            } catch (e: Exception) {
                qualities.value = listOf(Quality("Best", "best"))
                log("ERROR: ${e.message}")
                show("Error", "Failed to fetch formats. See log for details.")
            } finally {
                qualityIndex.value = 0
                qualityEnabled.value = true
            }
        }
    }

    /**
     * Reads yt-dlp's format table. Iwara offers muxed MP4s named "source", "540", "360" plus a short
     * "preview" clip, which is left out. Works with both the old ("|") and the new ("│") table style.
     */
    private fun parseFormats(output: String): List<Quality> {
        val result = mutableListOf<Quality>()
        var inSection = false
        for (line in output.lines()) {
            if ("ID" in line && "EXT" in line && "RESOLUTION" in line) { inSection = true; continue }
            if (!inSection) continue
            val l = line.trim()
            if (l.isEmpty() || l.all { it in "-─|│+ " }) continue
            val columns = l.substringBefore('│').substringBefore('|').trim().split(Regex("\\s+"))
            if (columns.size < 2) continue
            val id = columns[0]
            val ext = columns[1]
            val res = columns.getOrElse(2) { "" }
            if (id.equals("preview", ignoreCase = true) || res == "audio") continue
            val text = if (res.isNotEmpty() && res != "unknown") "$id: $ext @ $res" else "$id: $ext"
            result += Quality(text, id)
        }
        return result
    }

    // ---- download / cancel ----
    fun onDownloadClicked() {
        if (downloading.value) cancel() else startDownload()
    }

    private fun cancel() {
        cancelled = true
        log("\n--- Cancelling operation... Please wait ---")
        try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Exception) {}
        job?.cancel()
    }

    private fun startDownload() {
        val u = url.value.trim()
        if (u.isEmpty()) { show("Input Required", "Please enter a URL."); return }

        val fmt = format.value
        val link = checkLink(u, fmt) ?: return
        if (busy.value || !accountOk()) return

        val ident = when (fmt) {
            Fmt.MP4 -> qualities.value.getOrNull(qualityIndex.value)?.id ?: "best"
            Fmt.GIF -> "GIF"
            Fmt.MP3 -> "MP3"
            Fmt.IMAGE -> "Image"
        }

        // One history entry per post (canonical URL), so any URL form of the same post is recognised.
        if (history.contains(link.canonical, ident)) {
            show("Already Downloaded", "This post has already been downloaded with the selected quality/format ($ident).")
            return
        }

        cancelled = false
        downloading.value = true
        logText.value = ""
        launchJob { runDownload(link, fmt, ident) }
    }

    private suspend fun runDownload(link: Utils.IwaraLink, fmt: Fmt, ident: String) {
        val work = File(ctx.cacheDir, "work_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val count = if (fmt == Fmt.IMAGE) downloadImages(link, work) else downloadMedia(link, fmt, ident, work)
            history.append(link.canonical, ident)
            show("Success", "All operations finished successfully! ($count file(s) saved to Downloads/${MediaExporter.FOLDER})")
        } catch (e: CancellationException) {
            onCancelled()
            throw e
        } catch (e: Exception) {
            if (cancelled) onCancelled()
            else {
                log("\nERROR: ${e.message}")
                show("Download Failed", "The process failed. Please check the log for details.")
            }
        } finally {
            work.deleteRecursively()
            downloading.value = false
        }
    }

    private fun onCancelled() {
        log("\n--- Operation Cancelled by User ---")
        show("Cancelled", "The operation was cancelled.")
    }

    private suspend fun downloadMedia(link: Utils.IwaraLink, fmt: Fmt, ident: String, work: File): Int {
        val u = link.canonical
        log("--- Fetching video info to create filename... ---")
        val info = ytdlp(u) { addOption("--print", "%(uploader|)s|||%(title)s") }
            .lines().map { it.trim() }
            .firstOrNull { "|||" in it }
            ?: error("Could not read the video info. The video may be private (log in) or yt-dlp's Iwara support needs an update.")
        val uploader = info.substringBefore("|||").trim()
        val title = info.substringAfter("|||").trim()

        // "<uploader> - <title>"
        val base = if (uploader.isNotEmpty()) "$uploader - $title" else title
        val name = Utils.sanitizeFilename(base)
        log("Sanitized Filename: $name\n")

        // '%' must be escaped inside yt-dlp output templates
        val template = File(work, name.replace("%", "%%")).absolutePath + ".%(ext)s"

        when (fmt) {
            Fmt.MP4 -> {
                // Iwara serves complete MP4s ("source", "540", "360"), so no separate audio is merged.
                val sel = if (ident == "best") "source/Source/bv*+ba/b" else ident
                ytdlp(u) {
                    addOption("-o", template)
                    addOption("--format", sel)
                    addOption("--merge-output-format", "mp4")
                }
            }
            Fmt.GIF -> {
                log("--- Starting GIF Conversion ---")
                ytdlp(u) {
                    addOption("-o", template)
                    // a mid-size source is plenty for a 640px-wide GIF and converts much faster than "source"
                    addOption("--format", "540/360/bv*/b")
                    addOption("--recode-video", "gif")
                    // Same filter chain as the desktop app's ffmpeg call (-an drops any audio)
                    addOption(
                        "--postprocessor-args",
                        "VideoConvertor:-an -vf fps=15,scale=640:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse -loop 0"
                    )
                }
                log("--- GIF Conversion Complete ---")
            }
            Fmt.MP3 -> {
                log("--- Starting MP3 Conversion ---")
                ytdlp(u) {
                    addOption("-o", template)
                    // no audio-only stream on Iwara: take a small complete MP4 and extract its audio
                    addOption("--format", "ba/360/540/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
                log("--- MP3 Conversion Complete ---")
            }
            Fmt.IMAGE -> Unit
        }
        val finalExt = when (fmt) { Fmt.GIF -> "gif"; Fmt.MP3 -> "mp3"; else -> null }
        return exportOutputs(work, finalExt)
    }

    private suspend fun downloadImages(link: Utils.IwaraLink, work: File): Int {
        log("--- Starting image download (all images of the post) ---")
        val credentials = if (useAccount.value && account.hasAccount()) account.email to account.password else null
        val post = images.fetch(link.id, credentials)
        log("Found ${post.files.size} image(s)")
        var saved = 0
        post.files.forEachIndexed { i, file ->
            currentCoroutineContext().ensureActive()
            // "<uploader> - <title>_<num>.<ext>"
            val label = post.title.ifBlank { link.id }.take(50)
            val base = if (post.uploader.isNotEmpty()) "${post.uploader} - $label" else label
            val name = Utils.sanitizeFilename(base, 80) + "_${i + 1}"
            val f = File(work, "$name.${file.ext}")
            images.download(file.url, f)
            saved += exportOutputs(work)
        }
        return saved
    }

    /** Moves finished files from the work dir into Downloads/IwaraDownloader. */
    private fun exportOutputs(work: File, onlyExt: String? = null): Int {
        var n = 0
        work.listFiles()?.filter { f ->
            f.isFile && listOf("part", "ytdl", "temp").none { f.name.endsWith(".$it") }
        }?.forEach { f ->
            if (onlyExt != null && !f.extension.equals(onlyExt, true)) {
                log("Skipping leftover file: ${f.name}")
                f.delete()
                return@forEach
            }
            // Never overwrite: Name, Name_1, Name_2 ...
            val finalName = MediaExporter.uniqueName(ctx, f.nameWithoutExtension, f.extension)
            if (MediaExporter.export(ctx, f, finalName)) {
                log("Saved: $finalName")
                n++
            } else {
                log("ERROR: could not save $finalName")
            }
            f.delete()
        }
        return n
    }

    // ---- yt-dlp update (the Iwara extractor breaks whenever the site changes) ----
    fun updateYtDlp() {
        if (busy.value) return
        logText.value = "--- Updating yt-dlp (nightly channel, newest Iwara extractor fixes)... ---"
        launchJob {
            try {
                val status = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
                }
                log("Update result: $status")
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            }
        }
    }
}
