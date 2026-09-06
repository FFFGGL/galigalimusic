package com.galigalimusic.data.remote

import android.content.Context
import android.util.Base64

/** AList（WebDAV）连接配置，存私有 prefs */
object ExternalSources {
    private const val PREFS = "external_sources"
    private const val KEY_URL = "alist_url"
    private const val KEY_USER = "alist_user"
    private const val KEY_PASS = "alist_pass"

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "").orEmpty().trimEnd('/')

    fun getUser(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER, "").orEmpty()

    fun getPass(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASS, "").orEmpty()

    fun save(context: Context, url: String, user: String, pass: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, pass)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = getUrl(context).startsWith("http")

    /** WebDAV 基地址 = 服务器地址 + /dav */
    fun davBase(context: Context): String = getUrl(context) + "/dav"

    /** Basic 认证头；未配置用户名时返回空 map（AList 匿名目录也能浏览） */
    fun authHeaders(context: Context): Map<String, String> {
        val user = getUser(context)
        val pass = getPass(context)
        if (user.isEmpty() && pass.isEmpty()) return emptyMap()
        val raw = "$user:$pass"
        val token = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return mapOf("Authorization" to "Basic $token")
    }
}
