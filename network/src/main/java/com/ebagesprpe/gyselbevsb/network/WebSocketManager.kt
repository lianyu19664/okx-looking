package com.ebagesprpe.gyselbevsb.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket 管理器
 * 修复: 增加死链检测(Watchdog)、连接锁、自动重连
 * 变更: 增加更详细的日志输出
 * 2024-01 Fix: 解决僵尸连接循环和协程阻塞问题
 * 2026-01 Fix: 解决 connect() 导致的无限重连死循环 [FIXED]
 * 2026-01 Patch: 修复主动断开后仍因重连任务触发复活的僵尸服务 Bug
 */
class WebSocketManager(
    private val onMessageReceived: (String, JsonObject) -> Unit,
    private val onLog: (String) -> Unit,    // 关键日志回调
    private val onInfo: (String) -> Unit    // 运行状态回调
) {

    private var webSocket: WebSocket? = null
    // 使用独立的作用域进行心跳和重连管理
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private val isConnected = AtomicBoolean(false)
    // 新增：用户主动断开标志位，防止 disconnect 后无限重连
    private val isUserDisconnect = AtomicBoolean(false)
    private val gson = Gson()
    
    // 修复 Issue 1: 记录最后一次收到响应的时间 (应用层看门狗)
    @Volatile private var lastResponseTime = 0L

    // 记录已订阅的频道，用于断线重连后自动恢复
    private val subscribedAssets = ConcurrentHashMap.newKeySet<String>()

    /**
     * 建立连接
     * 修复 Issue 3: 增加 synchronized 锁并强制 cancel 旧实例，防止连接泄露
     * 修复 Infinite Loop: 清理旧连接时临时标记为主动断开，防止触发 onFailure 重连
     */
    @Synchronized
    fun connect() {
        // [修复] 先标记为 true，防止 cancel() 触发 onFailure 导致被误判为异常断开从而触发 scheduleReconnect
        isUserDisconnect.set(true)
        
        // 强制清理旧实例
        webSocket?.cancel() 
        webSocket = null
        isConnected.set(false)

        // 清理完毕，重置标志位，准备开始新的连接
        // ⚠️ 注意：这里重置得非常快，可能在旧连接的 onFailure 回调到达前就执行了
        isUserDisconnect.set(false)

        val request = Request.Builder()
            .url(ApiClient.getWsUrl())
            .build()

        onLog("Connecting to WebSocket...")
        // 切换为 wsClient
        webSocket = ApiClient.wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog("WS Connected Successfully")
                isConnected.set(true)
                lastResponseTime = System.currentTimeMillis() // 重置看门狗
                startHeartbeat()
                resubscribeAll()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 收到任何消息(Pong或Data)都视为连接存活
                lastResponseTime = System.currentTimeMillis()
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onLog("WS Closing: $reason (Code: $code)")
                isConnected.set(false)
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // [核心修复] 只有当前活跃的 Socket 关闭才处理，旧 Socket 的回调直接忽略
                if (webSocket !== this@WebSocketManager.webSocket) return

                onLog("WS Closed. (Code: $code)")
                isConnected.set(false)
                // 只有非主动断开才重连
                if (!isUserDisconnect.get()) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // [核心修复] 只有当前活跃的 Socket 报错才处理，旧 Socket 的回调直接忽略
                // 如果传入的 webSocket 实例不等于当前类持有的 webSocket 实例，说明这是上一个连接的遗言
                if (webSocket !== this@WebSocketManager.webSocket) return

                onLog("WS Failure: ${t.localizedMessage}")
                isConnected.set(false)
                // 只有非主动断开才重连
                if (!isUserDisconnect.get()) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun handleMessage(text: String) {
        // 心跳响应 pong (参考文档 2.2.2)
        if (text == "pong") {
            // onInfo("Heartbeat: Pong Received") // 降低 Pong 日志频率，避免刷屏
            return
        }

        try {
            val json = JsonParser.parseString(text).asJsonObject
            
            // 忽略事件响应
            if (json.has("event")) {
                val event = json.get("event").asString
                if (event == "subscribe") {
                    // onInfo("Subscription confirmed: " + json.get("arg").toString())
                }
                return
            }

            // 处理数据推送
            if (json.has("arg") && json.has("data")) {
                val arg = json.getAsJsonObject("arg")
                val channel = arg.get("channel").asString
                val instId = arg.get("instId").asString
                
                if (channel == "candle1H") {
                    onMessageReceived(instId, json)
                }
            }
        } catch (e: Exception) {
            // 解析错误忽略
        }
    }

    /**
     * 心跳保活机制 (Ping-Pong)
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                if (isConnected.get()) {
                    try {
                        // 修复 Issue 1: 看门狗检测连接假死
                        // 若超过 30秒 (1.5个周期) 未收到任何响应，判定为假死
                        if (System.currentTimeMillis() - lastResponseTime > 30_000) {
                            onLog("Error: WS Watchdog Triggered (No Data/Pong in 30s)")
                            // 看门狗触发视为异常断开，不设置 isUserDisconnect
                            webSocket?.cancel()
                            isConnected.set(false)
                            scheduleReconnect()
                            break
                        }

                        webSocket?.send("ping")
                        onInfo("Heartbeat: Ping Sent")
                    } catch (e: Exception) {
                        onLog("Heartbeat Error: ${e.message}")
                    }
                }
                delay(20000) // 每20秒发送一次 ping
            }
        }
    }

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        scope.launch {
            onInfo("Waiting 5s to reconnect...")
            delay(5000) // 5秒后重试
            // 重连前再次检查状态，防止重复连接
            // [修复] 必须同时检查 !isUserDisconnect.get()，防止在等待期间用户主动断开后仍然重连
            if (!isConnected.get() && !isUserDisconnect.get()) {
                onInfo("Reconnecting now...")
                connect()
            }
        }
    }

    fun subscribe(instIds: List<String>) {
        if (instIds.isEmpty()) return
        
        val toSubscribe = instIds.filter { subscribedAssets.add(it) }
        if (toSubscribe.isEmpty()) return

        onInfo("WS: Subscribing to ${toSubscribe.size} new channels...")

        if (isConnected.get()) {
            scope.launch {
                sendSubscribeRequest(toSubscribe)
            }
        } else {
            onInfo("WS: Queued subscription (Not connected)")
        }
    }

    fun unsubscribe(instIds: List<String>) {
        if (instIds.isEmpty()) return
        
        instIds.forEach { subscribedAssets.remove(it) }
        onInfo("WS: Unsubscribing from ${instIds.size} channels...")
        
        if (isConnected.get()) {
            instIds.chunked(50).forEach { batch ->
                val args = batch.map { mapOf("channel" to "candle1H", "instId" to it) }
                val payload = mapOf(
                    "op" to "unsubscribe",
                    "args" to args
                )
                sendJson(payload)
            }
        }
    }

    private fun resubscribeAll() {
        val allAssets = subscribedAssets.toList()
        if (allAssets.isNotEmpty()) {
            onLog("Recovery: Resubscribing to ${allAssets.size} assets...")
            scope.launch {
                sendSubscribeRequest(allAssets)
            }
        }
    }

    private suspend fun sendSubscribeRequest(instIds: List<String>) {
        instIds.chunked(40).forEach { batch ->
            val args = batch.map { mapOf("channel" to "candle1H", "instId" to it) }
            val payload = mapOf(
                "op" to "subscribe",
                "args" to args
            )
            sendJson(payload)
            delay(50)
        }
    }
    
    private fun sendJson(payload: Any) {
        try {
            webSocket?.send(gson.toJson(payload))
        } catch (e: Exception) {
            onLog("WS Send Error: ${e.message}")
        }
    }

    @Synchronized
    fun disconnect() {
        // 标记为用户主动断开
        isUserDisconnect.set(true)
        
        heartbeatJob?.cancel()
        isConnected.set(false)
        webSocket?.close(1000, "Normal Closure")
        webSocket?.cancel() // 确保释放资源
        webSocket = null
        // 注意：不清除 subscribedAssets，以便重连恢复
        onLog("WS Disconnected")
    }
}