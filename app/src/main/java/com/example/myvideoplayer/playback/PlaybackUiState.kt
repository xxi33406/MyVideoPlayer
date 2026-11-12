package com.example.myvideoplayer.playback

import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级的「播放页 UI 是否还活着」标记。
 *
 * 为什么需要它：[PlaybackService] 想在用户离开播放页后自行停止，释放
 * ExoPlayer 与解码器（实测常驻约 25MB），但它必须能区分两种情况：
 * - 播放页还在前台、用户只是按了暂停 → 不能停，否则用户按播放没反应
 * - 用户已经退出播放页 → 可以停
 *
 * 一开始想用 `MediaSession.connectedControllers` 判断，实测不可行：
 * 退出播放页后它仍然返回 1（MediaSessionService 内部还有 controller），
 * 永远等不到 0。
 *
 * [PlaybackService] 与 [com.example.myvideoplayer.ui.viewmodel.PlayerViewModel]
 * 都在主进程（Service 未声明 android:process），所以一个进程内单例就能
 * 精确共享这个状态，不必走 Binder 或自定义 SessionCommand。
 */
object PlaybackUiState {

    private val attachedCount = AtomicInteger(0)

    /** 播放页组合时调用。 */
    fun attach() {
        attachedCount.incrementAndGet()
    }

    /** 播放页销毁时调用。与 [attach] 必须成对。 */
    fun detach() {
        // 防御性下限：不会因为多调一次 detach 就变成负数，导致误判有 UI
        attachedCount.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun isUiAttached(): Boolean = attachedCount.get() > 0
}
