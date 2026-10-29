package com.iwaradownloader.app

import android.app.Application
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.thread

class App : Application() {

    companion object {
        /** Completes with null on success, or with an error message. */
        val ready = CompletableDeferred<String?>()

        /** True once the bundled aria2c is initialised; downloads fall back to yt-dlp's own downloader otherwise. */
        @Volatile var aria2Ready = false
    }

    override fun onCreate() {
        super.onCreate()
        thread(name = "ytdlp-init") {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                // aria2c is only a speed-up: a failure here must not break the app
                aria2Ready = try { Aria2c.getInstance().init(this); true } catch (e: Throwable) { false }
                ready.complete(null)
            } catch (e: Exception) {
                ready.complete("Failed to initialise yt-dlp: ${e.message}")
            }
        }
    }
}
