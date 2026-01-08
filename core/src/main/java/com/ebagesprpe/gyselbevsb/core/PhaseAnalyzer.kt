package com.ebagesprpe.gyselbevsb.core

import com.google.gson.JsonArray
import kotlin.math.abs

/**
 * 核心算法引擎：处理相变检测逻辑
 * 变更：增加 IC (瞬时变化) 和 NB (背景噪音) 的返回，便于日志分析
 */
object PhaseAnalyzer {
    
    data class AnalysisResult(
        val isTriggered: Boolean,
        val ver: Double = 0.0,
        val price: String = "0",
        val ic: Double = 0.0,
        val nb: Double = 0.0
    )

    /**
     * 分析 K 线数据 (适配 List<JsonArray> 结构)
     * @param candles 本地缓存的 K 线列表，Index 0 为最新
     * @param threshold 阈值 (VER_THRESHOLD)
     */
    fun analyzeCandles(candles: List<JsonArray>, threshold: Double): AnalysisResult {
        // [DESIGN DECISION] WONTFIX / 不予修复
        
        // 数据完整性检查 (需要至少 25 条：1条当前 + 24条历史)
        // [设计决策] 故意忽略上线不足 25 小时的新币
        // 理由：新币历史数据不足会导致噪音模型（NB）的分母计算失真，极易引发误报。
        // 本策略专注于成熟资产，为了系统稳定性，明确拒绝支持新币检测。请勿移除此限制。
        if (candles.size < 25) return AnalysisResult(false)

        val curr = candles[0]
        
        // OKX V5 K线结构: [ts, o, h, l, c, vol, ...]
        // Index: 2=High, 3=Low, 4=Close
        
        try {
            // IC (Instant Change): High - Low (当前K线振幅)
            val ic = curr[2].asDouble - curr[3].asDouble
            
            // NB (Noise Background): 过去 24 根 K 线的平均波动
            var sumNb = 0.0
            for (i in 1..24) {
                // 安全检查，防止列表长度不足
                if (i >= candles.size) break
                
                val k = candles[i]
                val kHigh = k[2].asDouble
                val kLow = k[3].asDouble
                sumNb += (kHigh - kLow)
            }
            val nb = sumNb / 24.0
            
            // 避免除零
            if (nb == 0.0) return AnalysisResult(false, ic = ic, nb = nb)

            // VER: 信号强度
            val ver = ic / nb
            val price = curr[4].asString // 当前最新价/收盘价
            
            if (ver >= threshold) {
                return AnalysisResult(true, ver, price, ic, nb)
            }
            
            // 即使未触发，也返回结果供 Watch 级日志记录
            return AnalysisResult(false, ver, price, ic, nb)
            
        } catch (e: Exception) {
            // 数值转换异常忽略
        }

        return AnalysisResult(false)
    }

    /**
     * 筛选资产池的逻辑
     */
    fun isAssetQualified(open: Double, last: Double): Boolean {
        if (open == 0.0) return false
        val change = abs((last - open) / open)
        // 涨跌幅限制在 30% 以内
        return change <= 0.30
    }
}