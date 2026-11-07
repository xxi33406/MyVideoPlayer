package com.example.myvideoplayer

import android.app.Application

class MyVideoPlayerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 提前建好容器，之后 ViewModel 通过 AppContainer.of(context) 复用同一份
        container = AppContainer.of(this)
    }
}
