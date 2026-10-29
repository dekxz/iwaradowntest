package com.iwaradownloader.app

import java.util.UUID

object Utils {
    enum class Kind { VIDEO, IMAGE }

    /** An Iwara post reduced to what matters: what it is and its id. */
    data class IwaraLink(val kind: Kind, val id: String) {
        /** One canonical URL per post, used for yt-dlp and as the history-log key. */
        val canonical: String
            get() = "https://www.iwara.tv/${if (kind == Kind.VIDEO) "video" else "image"}/$id"
    }

    /** iwara.tv / iwara.ai (and subdomains such as www. or the old ecchi.) video and image posts. */
    val IWARA_URL = Regex(
        """https?://(?:[a-z0-9-]+\.)?iwara\.(?:tv|ai)/(video|videos|image|images)/([A-Za-z0-9]+)(?:/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val BAD_CHARS = Regex("""[\\/*?:"<>|]""")
    private val SPACES = Regex("""\s+""")

    fun findIwaraUrl(text: String?): String? =
        text?.let { IWARA_URL.find(it)?.value }

    fun parse(text: String?): IwaraLink? {
        val m = text?.let { IWARA_URL.find(it) } ?: return null
        val kind = if (m.groupValues[1].lowercase().startsWith("image")) Kind.IMAGE else Kind.VIDEO
        return IwaraLink(kind, m.groupValues[2])
    }

    fun sanitizeFilename(name: String?, maxLength: Int = 60): String {
        if (name.isNullOrEmpty()) return "download_${UUID.randomUUID().toString().take(8)}"
        var s = BAD_CHARS.replace(name, "")
        s = SPACES.replace(s, "_").trim('_')
        if (s.length > maxLength) s = s.substring(0, maxLength)
        return s.trim('_').ifEmpty { "download_${UUID.randomUUID().toString().take(8)}" }
    }
}
