package com.handoverme.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.widget.Toast

class KioskManager(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin = ComponentName(
        context,
        HandoverDeviceAdminReceiver::class.java
    )

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    fun enter(activity: Activity): Boolean {
        if (!isDeviceOwner) {
            // Let Android decide how startLockTask() behaves on a device that
            // has not allowlisted this package. No app-generated test/fallback
            // message is shown; the actual system behavior is left visible for
            // testing and diagnosis.
            return try {
                activity.startLockTask()
                true
            } catch (_: Exception) {
                false
            }
        }

        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

        // Make Handover Me the persistent HOME activity while Secure Lending
        // Mode is active. This is the critical reboot path: after a restart
        // Android resolves HOME back to Handover Me instead of the normal
        // launcher. Device Owner provisioning is required for this API.
        val homeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MAIN)
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin,
            homeFilter,
            ComponentName(context, MainActivity::class.java)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, 0)
        }

        // Keep the status bar visible to match the Handover Me reference UI.
        // Lock Task still restricts Home/Overview; Device Owner policies provide
        // the production kiosk boundary.

        // Restrict common escape/configuration paths. These are device-owner
        // policies; availability varies by Android/OEM.
        addRestriction(UserManager.DISALLOW_SAFE_BOOT)
        addRestriction(UserManager.DISALLOW_FACTORY_RESET)
        addRestriction(UserManager.DISALLOW_ADD_USER)
        addRestriction(UserManager.DISALLOW_REMOVE_USER)
        addRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)

        activity.startLockTask()
        return true
    }

    fun exit(activity: Activity) {
        // stopLockTask also ends the test/screen-pinning path on a normal phone.
        try {
            activity.stopLockTask()
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        }

        if (!isDeviceOwner) return

        // Restore the user's normal launcher after the owner exits Secure Mode.
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        } catch (_: SecurityException) {
        }

        removeRestriction(UserManager.DISALLOW_SAFE_BOOT)
        removeRestriction(UserManager.DISALLOW_FACTORY_RESET)
        removeRestriction(UserManager.DISALLOW_ADD_USER)
        removeRestriction(UserManager.DISALLOW_REMOVE_USER)
        removeRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
    }

    fun reapplyAfterBoot(activity: Activity): Boolean {
        return enter(activity)
    }

    private fun addRestriction(restriction: String) {
        try {
            dpm.addUserRestriction(admin, restriction)
        } catch (_: SecurityException) {
        }
    }

    private fun removeRestriction(restriction: String) {
        try {
            dpm.clearUserRestriction(admin, restriction)
        } catch (_: SecurityException) {
        }
    }
}
