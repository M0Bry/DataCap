package com.meter.app

import android.app.Application
import com.meter.app.state.MeterEngine

/**
 * Boots the accounting engine as soon as the process exists — the Activity and the background
 * service both attach to it, so bytes are counted exactly once.
 */
class MeterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MeterEngine.init(this)
    }
}
