package com.kunk.singbox

import android.app.Application
import com.kunk.singbox.repository.LogRepository

class SingBoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogRepository.init(this)
    }
}
