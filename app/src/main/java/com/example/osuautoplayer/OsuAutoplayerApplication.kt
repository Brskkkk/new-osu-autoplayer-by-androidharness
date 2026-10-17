package com.example.osuautoplayer

import android.app.Application
import com.example.osuautoplayer.settings.PreferencesManager

class OsuAutoplayerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize preferences manager
        PreferencesManager.initialize(this)
    }
}
