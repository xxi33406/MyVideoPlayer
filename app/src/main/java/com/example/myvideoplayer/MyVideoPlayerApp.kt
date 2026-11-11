package com.example.myvideoplayer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.example.myvideoplayer.util.ThumbnailCache

class MyVideoPlayerApp : Application() {

    /** 监听省电模式开关，切换缩略图解码分辨率 */
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 这个广播没有携带状态 extra，收到后直接查系统当前值最可靠
            val on = context?.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            Log.i(TAG, "省电模式变更: $on")
            ThumbnailCache.setLowPowerMode(on)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 预热容器：之后 ViewModel 通过 AppContainer.of(context) 拿到同一份实例
        AppContainer.of(this)

        // 启动时同步一次当前状态，避免"已经在省电模式里却按正常模式跑"
        if (getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) {
            Log.i(TAG, "启动时处于省电模式，缩略图降分辨率")
            ThumbnailCache.setLowPowerMode(true)
        }
        registerReceiver(
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 系统报告内存吃紧时放掉缩略图内存缓存。
        // 缩略图有磁盘缓存兜底：清掉内存后重新滑动列表会从磁盘读 WebP，
        // 不需要重新解码视频帧，代价可控。不清的话，16MB 的 Bitmap
        // 会一直占着，低端机上容易连累整个 App 被回收。
        //
        // 这里不判断 level：ComponentCallbacks2 里区分前台/后台的那几个
        // 常量（TRIM_MEMORY_RUNNING_MODERATE 等）在 API 34 起已全部弃用，
        // 而 clear() 只是 evictAll()，真正重新加载走磁盘，成本足够低，
        // 对任何级别的 trim 一视同仁反而更简单。
        Log.i(TAG, "onTrimMemory($level)，清空缩略图内存缓存")
        ThumbnailCache.clear()
    }

    private companion object {
        const val TAG = "MyVideoPlayerApp"
    }
}
