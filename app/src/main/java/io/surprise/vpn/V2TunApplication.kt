package io.surprise.vpn

import android.app.Application
import io.surprise.vpn.data.AppRepository

class V2TunApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRepository.get(this)
    }
}
