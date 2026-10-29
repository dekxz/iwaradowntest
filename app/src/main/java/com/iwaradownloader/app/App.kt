package com.iwaradownloader.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.thread

class App : Application() {

    companion object {
        /** Completes with null on success, or with an error message. */
        val ready = CompletableDeferred<String?>()
    }

    override fun onCreate() {
        super.onCreate()
        thread(name = "ytdlp-init") {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                ready.complete(null)
            } catch (e: Exception) {
                ready.complete("Failed to initialise yt-dlp: ${e.message}")
            }
        }
    }
}
