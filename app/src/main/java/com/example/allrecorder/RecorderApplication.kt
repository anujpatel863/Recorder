package com.example.allrecorder

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RecorderApplication : Application() {
    override fun onCreate() {
        super.onCreate()

    }


}