package com.mobile.nodeide

import android.app.Application

class NodeSphereApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
