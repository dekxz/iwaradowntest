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

    // Characters Windows/Android forbid in names, plus control characters (same set as the desktop app).
    // CJK and every other Unicode letter is kept.
    private val BAD_CHARS = Regex("""[\\/*?:"<>|\u0000-\u001f]""")
    private val SPACES = Regex("""\s+""")

    fun findIwaraUrl(text: String?): String? =
        text?.let { IWARA_URL.find(it)?.value }

    fun parse(text: String?): IwaraLink? {
        val m = text?.let { IWARA_URL.find(it) } ?: return null
        val kind = if (m.groupValues[1].lowercase().startsWith("image")) Kind.IMAGE else Kind.VIDEO
        return IwaraLink(kind, m.groupValues[2])
    }

    /** Android file names are limited to 255 bytes (UTF-8); CJK is 3 bytes/char, emoji 4. Leave room for _N.ext. */
    private const val MAX_NAME_BYTES = 200

    /** Cuts to at most [maxChars] Unicode characters (code points, like Python's slicing), never splitting an emoji or rare CJK pair. */
    fun takeChars(s: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (s.codePointCount(0, s.length) <= maxChars) return s
        return s.substring(0, s.offsetByCodePoints(0, maxChars))
    }

    /** Cuts to at most [maxBytes] UTF-8 bytes on a character boundary. */
    private fun takeBytes(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    fun sanitizeFilename(name: String?, maxLength: Int = 60): String {
        if (name.isNullOrEmpty()) return "download_${UUID.randomUUID().toString().take(8)}"
        var s = BAD_CHARS.replace(name, "")
        s = SPACES.replace(s, "_").trim('_')
        s = takeChars(s, maxLength)
        s = takeBytes(s, MAX_NAME_BYTES)
        return s.trim('_').ifEmpty { "download_${UUID.randomUUID().toString().take(8)}" }
    }
}
