package com.ebagesprpe.gyselbevsb.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

object ApiClient {
    @Volatile
    var appVersion: String = "Unknown"

    // Cloudflare Workers 转发域名
    const val TARGET_HOST = "okx.835582.xyz"

    // 自定义 Header 常量
    private const val HEADER_KEY = "x-drvcshjx"
    private const val HEADER_VAL = "7#q07XKZ7qmBQ#h#"

    // 1. REST API 专用 Client
    val restClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            // [优化] 提升单主机并发限制至 40，允许应用层跑满 40个/2s 的限流额度
            // 具体的速率控制由 MonitorService 中的定速发射逻辑负责
            maxRequestsPerHost = 40
        }

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor())
            .retryOnConnectionFailure(true)
            .build()
    }

    // 2. WebSocket 专用 Client
    val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor())
            .retryOnConnectionFailure(true)
            .build()
    }
    
    fun getBaseUrl(): String {
        return "https://$TARGET_HOST/api/v5"
    }

    fun getWsUrl(): String {
        return "wss://$TARGET_HOST/ws/v5/business"
        // K线行情已经迁移到business频道
    }

    private class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val request = original.newBuilder()
                // [修改] 删除 User-Agent，请求更清爽
                .header(HEADER_KEY, HEADER_VAL)
                .method(original.method, original.body)
                .build()
            return chain.proceed(request)
        }
    }
}