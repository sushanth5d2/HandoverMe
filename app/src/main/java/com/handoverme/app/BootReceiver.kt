package com.handoverme.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Wait for BOOT_COMPLETED rather than launching before the user's
        // credential-protected data is unlocked. When the app is Device Owner
        // and Secure Mode was active, the persistent HOME policy also makes
        // Android resolve the launcher to Handover Me after reboot.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            val secureActive = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("handover_state", Context.MODE_PRIVATE)
                .getBoolean("secure_active", false)
            if (!secureActive) return

            val launch = Intent(context, MainActivity::class.java).apply {
                putExtra("restore_secure", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            try {
                context.startActivity(launch)
            } catch (_: Exception) {
                // If Android already launched MainActivity as the persistent
                // HOME activity, no second launch is necessary.
            }
        }
    }
}
