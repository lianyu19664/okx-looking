package com.ebagesprpe.gyselbevsb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ebagesprpe.gyselbevsb.ui.theme.AppColors

@Composable
fun TerminalCard(
    title: String,
    content: String,
    titleColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.BgCard),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        // 确保外部传入的 Modifier 能正确应用 (特别是 fillMaxWidth)
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题栏
            Text(
                text = title,
                color = titleColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.em,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.BgHeader)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )

            // 滚动内容区
            val scrollState = rememberScrollState()
            
            // 自动滚动到底部 (Auto-scroll logic)
            LaunchedEffect(content) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    // [修复核心：解决黑色背景填充不完整]
                    // 必须添加 fillMaxWidth，否则 Box 宽度默认由内容(文字)决定
                    // 这会导致文字短时背景也短，且背景只能覆盖文字区域
                    .fillMaxWidth()
                    .background(AppColors.BgTerminal)
                    .padding(8.dp)
            ) {
                Text(
                    text = content,
                    color = contentColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp,
                    modifier = Modifier.verticalScroll(scrollState)
                )
            }
        }
    }
}