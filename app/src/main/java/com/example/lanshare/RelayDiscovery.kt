package com.example.lanshare

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.Collections

/** 发现到的一个中继 */
data class RelayEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val version: String,
    val source: String          // "mDNS" 或 "广播"
) {
    val key: String get() = "$host:$port"
    val wsUrl: String get() = "ws://$host:$port"
}

/**
 * 局域网中继自动发现
 *
 * 双通道并行，互为兜底：
 *   mDNS  ：标准协议，由 NsdManager 原生搜索（部分路由器的组播抑制会让它失效）
 *   UDP   ：监听广播端口，穿透性优于组播，mDNS 搜不到时仍能发现
 *
 * 用法：start(sink) 后等待 onFinished；超时或外部调用 stop() 均会结束。
 */
class RelayDiscovery(private val ctx: Context) {

    companion object {
        const val SVC_TYPE = "_lanshare._tcp"
        const val BEACON_PORT = 41234
        private const val TAG = "RelayDiscovery"
        private const val TIMEOUT_MS = 5000L
    }

    interface Sink {
        fun onFound(ep: RelayEndpoint)
        fun onFinished(list: List<RelayEndpoint>)
        fun onError(msg: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val found = Collections.synchronizedMap(LinkedHashMap<String, RelayEndpoint>())
    private var sink: Sink? = null

    private val nsd: NsdManager? =
        ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var udpJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var started = false
    private var finished = false

    // NsdManager 一次只能解析一个服务，并发调用会失败 —— 用队列串行化
    private var resolving = false
    private val pendingResolve = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())

    fun start(sink: Sink) {
        this.sink = sink
        found.clear()
        finished = false
        started = true
        acquireLock()
        startNsd()
        startUdp()
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        finish()
    }

    @Synchronized
    private fun finish() {
        if (finished) return
        finished = true
        stopNsd()
        stopUdp()
        releaseLock()
        val snapshot = synchronized(found) { found.values.toList() }
            .sortedWith(compareBy({ it.source != "mDNS" }, { it.name }))
        handler.post { sink?.onFinished(snapshot) }
    }

    private fun add(ep: RelayEndpoint) {
        if (ep.port <= 0 || ep.host.isBlank()) return
        val isNew = synchronized(found) { found.put(ep.key, ep) == null }
        if (isNew) handler.post { sink?.onFound(ep) }
    }

    private fun reportError(msg: String) {
        handler.post { sink?.onError(msg) }
    }

    // ==================== mDNS ====================
    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
            Log.w(TAG, "解析失败: ${si.serviceName} code=$code")
            nextResolve()
        }

        override fun onServiceResolved(si: NsdServiceInfo) {
            try {
                val host = si.host?.hostAddress ?: return
                val attrs = si.attributes ?: emptyMap()
                val name = attrs["name"]?.let { String(it, Charsets.UTF_8) }
                    ?.takeIf { it.isNotBlank() } ?: si.serviceName
                val ver = attrs["version"]?.let { String(it, Charsets.UTF_8) } ?: ""
                add(RelayEndpoint(name, host, si.port, ver, "mDNS"))
            } catch (e: Exception) {
                Log.w(TAG, "解析结果异常", e)
            } finally {
                nextResolve()
            }
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(t: String, c: Int) {
            Log.w(TAG, "mDNS 启动失败 code=$c")
            reportError("mDNS 启动失败（code=$c），将仅用广播发现")
        }
        override fun onStopDiscoveryFailed(t: String, c: Int) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}

        override fun onServiceFound(si: NsdServiceInfo) {
            synchronized(pendingResolve) { pendingResolve.add(si) }
            nextResolve()
        }
        override fun onServiceLost(si: NsdServiceInfo) {}
    }

    private fun startNsd() {
        try {
            nsd?.discoverServices(SVC_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS 发现异常", e)
        }
    }

    private fun nextResolve() {
        if (resolving) return
        val next = synchronized(pendingResolve) {
            if (pendingResolve.isEmpty()) null else pendingResolve.removeAt(0)
        } ?: return
        if (nsd == null) { resolving = false; return }
        resolving = true
        try {
            nsd.resolveService(next, resolveListener)
        } catch (e: Exception) {
            resolving = false   // 失败则解锁，避免队列卡死
        }
    }

    private fun stopNsd() {
        try {
            nsd?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "停止 mDNS 发现异常", e)
        }
        synchronized(pendingResolve) { pendingResolve.clear() }
    }

    // ==================== UDP 广播 ====================
    private fun startUdp() {
        udpJob = CoroutineScope(Dispatchers.IO).launch {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(BEACON_PORT))
                }
                udpSocket = s
                val buf = ByteArray(2048)
                while (isActive) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (p.length <= 0) continue
                    val text = String(p.data, 0, p.length, Charsets.UTF_8)
                    val o = JSONObject(text)
                    // 只认自家信标，避免把别的协议广播当成中继
                    if (o.optString("type") != "lanshare-relay") continue
                    val host = p.address?.hostAddress ?: continue
                    val port = o.optInt("port", 0)
                    val name = o.optString("name").takeIf { it.isNotBlank() } ?: host
                    val ver = o.optString("version", "")
                    withContext(Dispatchers.Main) {
                        add(RelayEndpoint(name, host, port, ver, "广播"))
                    }
                }
            } catch (e: Exception) {
                // 端口被占用或协程取消：静默结束，不影响 mDNS 结果
                Log.d(TAG, "UDP 监听结束: ${e.message}")
            } finally {
                try { s?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun stopUdp() {
        udpJob?.cancel()
        udpJob = null
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
    }

    // ==================== 组播锁 ====================
    private fun acquireLock() {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("lanshare-discovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "组播锁获取失败（不影响广播通道）", e)
        }
    }

    private fun releaseLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "组播锁释放异常", e)
        }
        multicastLock = null
    }
}
