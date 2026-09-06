package com.example.lanshare

import android.app.Application

/**
 * 全局 Application：持有唯一一条 WebSocket 连接
 * 界面（MainActivity）与前台服务（RelayService）共享它，避免重复建连
 */
class LanShareApp : Application() {

    /** 全局唯一的连接管理器 */
    val wsManager: WebSocketManager by lazy { WebSocketManager() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注入设备身份：此后每条发出的消息都会携带 sender{id,name}，
        // 同一频道内的其他设备才能区分消息来源（不再只有「我/他人」）
        wsManager.sender = DeviceIdentity.sender(this)
    }

    /**
     * 修改本机设备名并同步到连接层
     * 改名后新消息立即生效，无需重连
     */
    fun updateDeviceName(name: String) {
        DeviceIdentity.setName(this, name)
        wsManager.sender = DeviceIdentity.sender(this)
    }

    companion object {
        lateinit var instance: LanShareApp
            private set
    }
}
