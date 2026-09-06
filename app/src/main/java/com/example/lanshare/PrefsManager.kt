package com.example.lanshare

import android.content.Context

object PrefsManager {
    private const val PREF = "lanshare"
    private const val KEY_URL = "ws_url"
    private const val KEY_CHANNEL = "default_channel"
    private const val KEY_PASSWORDS = "passwords"
    private const val DEF_URL = "ws://192.168.1.100:8080"
    private const val DEF_CHANNEL = "0000"

    fun getUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEF_URL) ?: DEF_URL

    fun setUrl(ctx: Context, url: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url).apply()

    fun getChannel(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_CHANNEL, DEF_CHANNEL) ?: DEF_CHANNEL

    fun setChannel(ctx: Context, channel: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHANNEL, channel).apply()

    // ===== 频道口令（本地保存，发送到中继做校验）=====
    fun getPassword(ctx: Context, channel: String): String {
        val all = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_PASSWORDS, null)
        if (all.isNullOrEmpty()) return ""
        return try {
            org.json.JSONObject(all).optString(channel, "")
        } catch (e: Exception) { "" }
    }

    fun setPassword(ctx: Context, channel: String, pwd: String) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val all = sp.getString(KEY_PASSWORDS, null)
        val obj = try {
            if (all.isNullOrEmpty()) org.json.JSONObject() else org.json.JSONObject(all)
        } catch (e: Exception) { org.json.JSONObject() }
        obj.put(channel, pwd)
        sp.edit().putString(KEY_PASSWORDS, obj.toString()).apply()
    }
}
