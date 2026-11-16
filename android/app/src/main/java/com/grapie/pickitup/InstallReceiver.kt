package com.grapie.pickitup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Result of [Updater]'s installer session: shows the system confirmation when it is needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // First self-update, or "install unknown apps" not allowed yet: the system asks.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) Updater.onInstallEnded(status, "no confirmation intent")
                // Android blocks activity starts from the background (the user left during the
                // download): then the next screen of ours to resume shows it.
                else if (BaseActivity.resumed > 0) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else Updater.deferConfirm(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // the new version replaces this process
            else -> Updater.onInstallEnded(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
