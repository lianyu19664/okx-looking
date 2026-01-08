package com.ebagesprpe.gyselbevsb.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

// 新增跨模块引用
import com.ebagesprpe.gyselbevsb.network.ApiClient
import com.ebagesprpe.gyselbevsb.core.PhaseAnalyzer

class MarketRepository {
    private val gson = Gson()

    /**
     * 获取符合条件的资产列表 (Discovery Mode)
     * 修复: 使用 restClient 确保超时控制
     */
    suspend fun fetchQualifiedTickers(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiClient.getBaseUrl()}/market/tickers?instType=SWAP")
            .build()

        // 切换为 restClient
        ApiClient.restClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP Error: ${response.code} ${response.message}")
            }
            
            val bodyStr = response.body?.string() ?: throw IOException("Empty Response Body")
            val json = gson.fromJson(bodyStr, JsonObject::class.java)

            // [修复] 增加 isJsonNull 检查，防止 data: null 导致 getAsJsonArray 崩溃
            if (!json.has("data") || json.get("data").isJsonNull) {
                val msg = if (json.has("msg")) json.get("msg").asString else "No Data Field"
                throw IOException("API Error: $msg")
            }

            return@withContext json.getAsJsonArray("data")
                .map { it.asJsonObject }
                .filter {
                    val instId = it.get("instId").asString
                    // 过滤掉稳定币对
                    !instId.matches(Regex("^(USDT|USDC|DAI|FDUSD|TUSD)-.*"))
                }
                .filter {
                    // [修复] 增加 isJsonNull 安全检查，防止 asDouble 崩溃
                    val open = if (it.has("open24h") && !it.get("open24h").isJsonNull) 
                        it.get("open24h").asDouble else 0.0
                    val last = if (it.has("last") && !it.get("last").isJsonNull) 
                        it.get("last").asDouble else 0.0
                    PhaseAnalyzer.isAssetQualified(open, last)
                }
                .sortedByDescending { 
                    // [修复] 业务逻辑 BUG：原先仅按数量排序导致垃圾币霸榜
                    // 改为按成交额 (Vol * Price) 排序，确保筛选出流动性最好的资产
                    //volCcy24h * last是正确的计算方式
                    // [修复] 增加 isJsonNull 安全检查
                    val vol = if (it.has("volCcy24h") && !it.get("volCcy24h").isJsonNull) 
                        it.get("volCcy24h").asDouble else 0.0
                    val last = if (it.has("last") && !it.get("last").isJsonNull) 
                        it.get("last").asDouble else 0.0
                    vol * last
                }
                .take(120) // 限制 WS 订阅总数
                .map { it.get("instId").asString }
        }
    }

    /**
     * 获取历史 K 线 (Snapshot)
     * 修复: 使用 restClient 确保超时控制
     */
    suspend fun fetchHistoryCandles(instId: String): JsonArray? = withContext(Dispatchers.IO) {
        val url = "${ApiClient.getBaseUrl()}/market/candles?instId=$instId&bar=1H&limit=30"
        val request = Request.Builder().url(url).build()

        try {
            // 切换为 restClient
            ApiClient.restClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bodyStr = response.body?.string() ?: return@use null
                val json = gson.fromJson(bodyStr, JsonObject::class.java)
                
                // [修复] 同样增加空值检查，增强健壮性
                if (json.has("data") && !json.get("data").isJsonNull) {
                    json.getAsJsonArray("data") 
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}