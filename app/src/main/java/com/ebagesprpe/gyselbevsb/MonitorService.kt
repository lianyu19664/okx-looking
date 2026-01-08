package com.ebagesprpe.gyselbevsb

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 引用其他模块
import com.ebagesprpe.gyselbevsb.data.MarketRepository
import com.ebagesprpe.gyselbevsb.network.WebSocketManager
import com.ebagesprpe.gyselbevsb.core.PhaseAnalyzer
import com.ebagesprpe.gyselbevsb.network.ApiClient

class MonitorService : Service() {

    private var serviceJob: CompletableJob? = null
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val repository = MarketRepository()
    
    // 目标资产池
    private val targetPool = ConcurrentHashMap.newKeySet<String>()
    
    // K线本地缓存
    private val candleCache = ConcurrentHashMap<String, LinkedList<JsonArray>>()

    // 报警和日志记录
    private val lastTriggeredTs = ConcurrentHashMap<String, String>()
    // [修复 2] 新增：记录上一次报警时的 VER 值，用于检测信号增强
    private val lastTriggeredVer = ConcurrentHashMap<String, Double>()

    private val lastWatchTs = ConcurrentHashMap<String, String>()
    
    private lateinit var wsManager: WebSocketManager
    
    companion object {
        const val CHANNEL_ID = "monitor_channel"
        
        // [修改] 更改 ID 以应用新的静音配置 (旧 ID 的配置是不可变的)
        const val ALERT_CHANNEL_ID = "alert_channel_silent_pop"
        
        const val LOG_ACTION = "com.ebagesprpe.LOG_UPDATE"
        const val INFO_ACTION = "com.ebagesprpe.INFO_UPDATE"
        const val STATUS_ACTION = "com.ebagesprpe.STATUS_UPDATE"
        
        const val NOTIFICATION_ID = 999
        const val VER_THRESHOLD = 3.0 
        
        @Volatile
        var isServiceRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = false
        
        ApiClient.appVersion = BuildConfig.VERSION_NAME
        
        wsManager = WebSocketManager(
            onMessageReceived = { instId, json -> handleWsMessage(instId, json) },
            onLog = { msg -> sendLog(msg) },
            onInfo = { msg -> sendInfo(msg) }
        )
        
        initScope()
        createChannels()
        
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Detector:Engine")
        wakeLock?.setReferenceCounted(false)
        sendInfo("Service Created. WakeLock initialized.")
    }

    private fun initScope() {
        if (serviceJob?.isActive != true) {
            serviceJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + serviceJob!!)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()

        if (isServiceRunning) {
            sendLog("服务保活检测: 运行中 (WS Mode)")
            broadcastStatus(true)
            wsManager.connect() 
            return START_STICKY
        }

        isServiceRunning = true
        broadcastStatus(true)
        wakeLock?.acquire()
        sendInfo("WakeLock Acquired. Starting Main Engine...")
        
        initScope()
        startEngine()
        
        return START_STICKY
    }

    private fun startEngine() {
        sendLog(">>> 引擎启动 | 协议: WebSocket | Mode: High-Speed Sync | Ver: ${BuildConfig.VERSION_NAME}")
        
        wsManager.connect()
        
        scope?.launch {
            delay(1000)
            while (isActive) {
                try {
                    updateNotification("同步数据...", "正在筛选优质资产...")
                    sendInfo("Macro Scan: 开始全市场扫描...")
                    
                    val startTime = System.currentTimeMillis()
                    val newAssets = repository.fetchQualifiedTickers()
                    val duration = System.currentTimeMillis() - startTime
                    
                    sendInfo("Macro Scan: 扫描完成，耗时 ${duration}ms，发现 ${newAssets.size} 个活跃资产")
                    
                    if (newAssets.isNotEmpty()) {
                        refreshAssetPool(newAssets)
                    } else {
                        sendInfo("Macro Scan Warning: 资产池获取为空，保持现有状态")
                    }
                    
                    sendInfo("Cycle End: 进入 300秒 休眠等待下一轮扫描")
                    delay(5 * 60 * 1000) 
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    
                    sendLog("Macro Error: ${e.message}")
                    e.printStackTrace()
                    delay(30000) 
                }
            }
        }
    }

    private suspend fun refreshAssetPool(newAssets: List<String>) {
        val currentSet = targetPool.toSet()
        val newSet = newAssets.toSet()
        
        val toRemove = currentSet - newSet
        val toAdd = newSet - currentSet
        val toKeep = currentSet.intersect(newSet)
        
        sendInfo("Pool Analysis: Keep=${toKeep.size} | +${toAdd.size} | -${toRemove.size}")
        
        // 1. 处理移除
        if (toRemove.isNotEmpty()) {
            wsManager.unsubscribe(toRemove.toList())
            toRemove.forEach { 
                targetPool.remove(it)
                candleCache.remove(it)
                lastTriggeredTs.remove(it)
                lastTriggeredVer.remove(it) // 清理 VER 记录
                lastWatchTs.remove(it)
            }
            val preview = toRemove.take(5).joinToString(",")
            sendLog("Pool Clean: 移除 ${toRemove.size} 个过期资产 ($preview...)")
        }
        
        // 2. 处理新增
        if (toAdd.isNotEmpty()) {
            sendInfo("Pool Expand: 发现 ${toAdd.size} 个新资产，准备接入...")
            val preview = toAdd.take(5).joinToString(",")
            sendLog("New Assets: $preview${if (toAdd.size > 5) "..." else ""}")
            
            toAdd.forEach { 
                targetPool.add(it)
                candleCache[it] = LinkedList() 
            }

            val listToAdd = toAdd.toList()
            wsManager.subscribe(listToAdd)
            sendLog("Subscribe: 优先订阅 ${toAdd.size} 个资产流 (防止漏单)")

            if (listToAdd.isNotEmpty()) {
                val total = listToAdd.size
                // [优化] 提升并发槽位到 40，防止网络卡顿时任务积压，同时匹配 2s 的突发窗口
                val concurrencyLimit = Semaphore(40)
                val counter = AtomicInteger(0)

                sendInfo("Backfill Start: 目标 $total 个 | 策略: 20 TPS (定速发射)")
                
                supervisorScope {
                    listToAdd.forEach { instId ->
                        // [核心优化] 速率控制 (Pacing)
                        // 40个/2s = 20个/1s => 每个请求间隔 1000ms / 20 = 50ms
                        // 在启动协程前等待，确保发射速率严格控制在 API 阈值之下，避免 429
                        delay(50)

                        launch {
                            try {
                                concurrencyLimit.withPermit {
                                    // [优化] 移除内部 delay(100)，获得令牌后立即全速请求
                                    val history = repository.fetchHistoryCandles(instId)
                                    if (history != null && history.size() > 0) {
                                        mergeHistoryToCache(instId, history)
                                    } else {
                                        // 记录空数据警告
                                        // sendLog("Backfill Warn: No History for $instId")
                                    }
                                    
                                    val currentCount = counter.incrementAndGet()
                                    // 稍微降低日志频率，每 20 个报一次
                                    if (currentCount % 20 == 0 || currentCount == total) {
                                        sendInfo("Backfill Progress: $currentCount / $total")
                                    }
                                }
                            } catch (e: Exception) {
                                // [修复 3] 增强 SYSTEM STATUS 的报错显示
                                val errMsg = e.message ?: "Unknown"
                                if (errMsg.contains("429") || errMsg.contains("Too Many Requests")) {
                                    sendInfo("⚠️ API Limit (429): $instId - 暂停回填")
                                } else if (errMsg.contains("timeout", ignoreCase = true)) {
                                    sendInfo("⚠️ Timeout: $instId")
                                }
                                sendLog("Backfill Fail [$instId]: $errMsg")
                            }
                        }
                    }
                }
                sendInfo("Backfill Complete: 全部 $total 个资产历史数据同步完成")
            }
        }
        
        updateNotification("监控运行中", "资产池: ${targetPool.size} | WS 活跃")
    }

    private fun mergeHistoryToCache(instId: String, history: JsonArray) {
        val cache = candleCache[instId] ?: return
        
        synchronized(cache) {
            if (cache.isEmpty()) {
                history.forEach { cache.add(it.asJsonArray) }
            } else {
                val cachedTimestamps = cache.map { it[0].asString.toLong() }.toSet()
                history.forEach { 
                    val item = it.asJsonArray
                    val ts = item[0].asString.toLong()
                    if (!cachedTimestamps.contains(ts)) {
                        cache.add(item)
                    }
                }
                Collections.sort(cache) { a, b -> 
                    val t1 = a[0].asString.toLong()
                    val t2 = b[0].asString.toLong()
                    t2.compareTo(t1) 
                }
            }
            while (cache.size > 30) cache.removeLast()
        }
    }

    private fun handleWsMessage(instId: String, json: JsonObject) {
        try {
            val dataArr = json.getAsJsonArray("data")
            if (dataArr.size() == 0) return
            
            val newCandle = dataArr[0].asJsonArray
            val history = candleCache[instId] ?: return
            
            synchronized(history) {
                if (history.isEmpty()) {
                    history.addFirst(newCandle)
                } else {
                    val lastTs = history.first[0].asString
                    val newTs = newCandle[0].asString
                    
                    if (newTs == lastTs) {
                        history[0] = newCandle
                    } else {
                        history.addFirst(newCandle)
                        if (history.size > 30) {
                            history.removeLast()
                        }
                    }
                }
                
                val snapshot = ArrayList(history)
                val result = PhaseAnalyzer.analyzeCandles(snapshot, VER_THRESHOLD)
                
                val currentTs = snapshot[0][0].asString

                if (result.isTriggered) {
                    val lastTs = lastTriggeredTs[instId]
                    val lastVer = lastTriggeredVer[instId] ?: 0.0
                    
                    // [修复 2] 报警频率优化逻辑
                    // 条件1: 新的小时 (时间戳变更)
                    val isNewHour = (currentTs != lastTs)
                    // 条件2: 信号显著增强 (当前 VER 比上次记录高出 20% 以上)
                    val isIntensified = (result.ver > lastVer * 1.2)

                    if (isNewHour || isIntensified) {
                        lastTriggeredTs[instId] = currentTs
                        lastTriggeredVer[instId] = result.ver
                        
                        // 如果是同小时内的增强信号，日志做个标记
                        val logPrefix = if (!isNewHour) "[UP] " else ""
                        triggerAlert(instId, result.ver, result.price, result.ic, result.nb, logPrefix)
                    }
                } else {
                    // 观察日志 (Watch Log)
                    if (result.ver > VER_THRESHOLD * 0.7) {
                         val lastTs = lastWatchTs[instId]
                         if (currentTs != lastTs) {
                             lastWatchTs[instId] = currentTs
                             sendLog("Watch [$instId]: VER=%.2f (IC=%.2f, NB=%.2f)".format(result.ver, result.ic, result.nb))
                         }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "Handle Msg Error", e)
        }
    }

    private fun triggerAlert(instId: String, ver: Double, price: String, ic: Double, nb: Double, prefix: String = "") {
        val msg = "VER: %.2f | Price: %s | IC: %.2f | NB: %.2f".format(ver, price, ic, nb)
        sendLog("!!! ALERT [$instId] $prefix$msg !!!")
        
        val intent = Intent(this, MainActivity::class.java)
        val pIntent = PendingIntent.getActivity(this, instId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("⚠ 相变警告: $instId")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 对应 Channel 的 IMPORTANCE_HIGH
            // [修改] 移除 DEFAULT_ALL，因为默认包含了声音和震动
            // .setDefaults(Notification.DEFAULT_ALL) 
            .setContentIntent(pIntent)
            .setAutoCancel(true)
            .build()
            
        try {
            // [修复 1] Notification ID 碰撞检测
            // 防止 ID 与前台服务 ID (999) 冲突导致 Service 被系统误杀
            var notifyId = instId.hashCode()
            if (notifyId == NOTIFICATION_ID) {
                notifyId += 1
            }
            getSystemService(NotificationManager::class.java).notify(notifyId, notification)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun sendLog(msg: String) {
        val intent = Intent(LOG_ACTION).apply { 
            putExtra("msg", msg)
            setPackage(packageName) 
        }
        sendBroadcast(intent)
    }
    
    private fun sendInfo(msg: String) {
        val intent = Intent(INFO_ACTION).apply {
            putExtra("msg", msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
    
    private fun broadcastStatus(running: Boolean) {
        val intent = Intent(STATUS_ACTION).apply {
            putExtra("running", running)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun startForegroundSafely() {
        val notification = buildStatusNotification("初始化中", "正在启动 WS 引擎...")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "StartForeground Error", e)
        }
    }

    private fun buildStatusNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        try {
            mgr?.notify(NOTIFICATION_ID, buildStatusNotification(title, text))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun createChannels() {
        val mgr = getSystemService(NotificationManager::class.java)

        // 1. 服务保活渠道 (Low Importance: 静音且不弹窗)
        val svcChan = NotificationChannel(CHANNEL_ID, "Service Status", NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(svcChan)

        // 2. 报警通知渠道 (High Importance: 弹出横幅)
        // [修改] 显式关闭声音和震动，实现“静音弹窗”
        val alertChan = NotificationChannel(ALERT_CHANNEL_ID, "Phase Alerts (Silent)", NotificationManager.IMPORTANCE_HIGH)
        alertChan.setSound(null, null)    // 关键：无声音
        alertChan.enableVibration(false)  // 关键：无震动
        mgr.createNotificationChannel(alertChan)
    }

    override fun onDestroy() {
        super.onDestroy()
        wsManager.disconnect()
        serviceJob?.cancel()
        serviceJob = null
        scope = null
        
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "WakeLock Release Error", e)
        }
        
        isServiceRunning = false
        broadcastStatus(false)
        sendLog("服务已停止 (Destroyed)")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}