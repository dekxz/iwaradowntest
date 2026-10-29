package com.iwaradownloader.app

import android.content.Context

/**
 * Keeps the user's Iwara login (email + password) in the app's private storage.
 * Iwara does not use browser cookies for access: yt-dlp logs in with --username/--password and the
 * image downloader logs in through the Iwara API, so a cookies.txt is not needed.
 */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("iwara_account", Context.MODE_PRIVATE)

    val email: String get() = prefs.getString(KEY_EMAIL, "").orEmpty()
    val password: String get() = prefs.getString(KEY_PASSWORD, "").orEmpty()

    fun hasAccount(): Boolean = email.isNotEmpty() && password.isNotEmpty()

    fun save(email: String, password: String) {
        prefs.edit().putString(KEY_EMAIL, email).putString(KEY_PASSWORD, password).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun statusText(): String =
        if (hasAccount()) "Logged in as $email" else "Not logged in (public videos and images only)"

    private companion object {
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
