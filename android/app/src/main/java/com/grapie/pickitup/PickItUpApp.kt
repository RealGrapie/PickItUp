package com.grapie.pickitup

import android.app.Application

class PickItUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.d("app", "process start, version " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull())
        DebugLog.lastExit(this)
        if (resources.getBoolean(R.bool.webview_debug)) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        AppSettings.init(this)
        BarcodeStore.init(this)
        Accounts.init(this)  // after BarcodeStore (demo mode); migrates the 1.x login
        Watches.init(this)
        WearSync.init(this)  // after Accounts
        Updater.init(this)
        Net.init(this)
        SessionProbe.init(this)
        RefreshJobService.schedule(this)
    }
}
