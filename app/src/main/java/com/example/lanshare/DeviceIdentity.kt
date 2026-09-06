package com.example.lanshare

import android.content.Context
import android.os.Build
import java.util.UUID

/** 附带在每条消息上的发送者信息（与浏览器端 sender 字段结构一致） */
data class Sender(val id: String, val name: String)

/**
 * 设备身份：跨会话持久的唯一 ID 与可自定义名称
 * 用于在同一频道内区分不同发送者（多人同频道时不再只有「我/他人」）
 */
object DeviceIdentity {

    private const val PREF = "device"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"

    /** 唯一设备 ID，首次调用时生成并落盘 */
    @Synchronized
    fun id(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        sp.edit().putString(KEY_ID, newId).apply()
        return newId
    }

    /** 默认设备名，如 "Pixel 7 · Android" */
    fun defaultName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return "$model · Android"
    }

    fun name(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()
    }

    fun setName(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, value.trim()).apply()
    }

    fun sender(ctx: Context): Sender = Sender(id(ctx), name(ctx))
}
