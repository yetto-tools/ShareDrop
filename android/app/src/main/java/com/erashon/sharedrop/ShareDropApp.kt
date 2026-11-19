package com.erashon.sharedrop

import android.app.Application
import com.erashon.sharedrop.discovery.HotspotController

class ShareDropApp : Application() {
    lateinit var engine: Engine
        private set
    lateinit var hotspot: HotspotController
        private set

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
        hotspot = HotspotController(this)
    }
}
