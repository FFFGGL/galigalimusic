package com.galigalimusic.data.remote

import android.content.Context

/** WebDAV 备份配置（地址/账号/密码/自动备份开关） */
object WebDavPrefs {

    private const val PREFS = "webdav_prefs"
    private const val KEY_URL = "url"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_AUTO = "auto_backup"

    fun url(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "").orEmpty()

    fun user(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER, "").orEmpty()

    fun pass(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASS, "").orEmpty()

    fun autoBackup(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, false)

    fun isConfigured(context: Context): Boolean =
        url(context).isNotBlank() && user(context).isNotBlank() && pass(context).isNotBlank()

    fun save(context: Context, url: String, user: String, pass: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, pass)
            .apply()
    }

    fun setAutoBackup(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, on)
            .apply()
    }
}
