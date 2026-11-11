package com.example.myvideoplayer.ui.screen.player

import java.util.Locale

/** 毫秒转 mm:ss。超过一小时也不显示小时位——本地短视频基本用不到。 */
internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * 倍速文本。统一保留一位小数并带 x 后缀：
 * 原来整数倍速返回 "3"（不带单位），屏幕上孤零零一个数字，
 * 不看上下文根本不知道那是倍速。
 */
internal fun formatSpeed(speed: Float): String = String.format(Locale.US, "%.1fx", speed)
