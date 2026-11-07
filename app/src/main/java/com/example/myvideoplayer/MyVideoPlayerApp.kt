package com.example.myvideoplayer

import android.app.Application

class MyVideoPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 预热容器：之后 ViewModel 通过 AppContainer.of(context) 拿到同一份实例
        AppContainer.of(this)
    }
}
