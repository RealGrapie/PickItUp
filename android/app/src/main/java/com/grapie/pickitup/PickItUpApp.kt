package com.grapie.pickitup

import android.app.Application

class PickItUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        BarcodeStore.init(this)
        Watches.init(this)
        RefreshJobService.schedule(this)
    }
}
