package com.eldermoss.hermes

import android.app.Application

class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
