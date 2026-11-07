package com.example.myvideoplayer

import android.content.Context
import com.example.myvideoplayer.data.repository.VideoRepository

/**
 * 应用级依赖容器。
 *
 * 所有跨 ViewModel 共享的依赖都从这里取，避免每个 ViewModel 各自
 * `VideoRepository(context)` 造出重复实例，也让后续替换实现（测试、换内核）有单一入口。
 *
 * 这里刻意不引入 Hilt/Koin：当前只有 Repository 一个依赖，
 * 手写容器足够，且不增加编译期成本。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Room + SAF 相关读写，全局共用一个实例 */
    val videoRepository: VideoRepository by lazy { VideoRepository(appContext) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        /**
         * 取全局容器。首个调用者（通常是 Application.onCreate）负责创建，
         * 之后一律复用，因此 ViewModel 不必强转 Application 类型。
         */
        fun of(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
