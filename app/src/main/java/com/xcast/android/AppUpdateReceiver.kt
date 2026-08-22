package com.xcast.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdate.SESSION_ACTION) return
        val extras = intent.extras ?: return
        when (extras.getInt(PackageInstaller.EXTRA_STATUS)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    extras.getParcelable(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelable(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { }
            else -> {
                val path = extras.getString("file")
                val file = path?.let { File(it) }
                if (file != null && file.exists()) {
                    try {
                        AppUpdate.installPrompt(context, file)
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
