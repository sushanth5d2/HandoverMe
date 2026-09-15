package com.handoverme.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

class CallStateManager(
    private val context: Context,
    private val onStateChanged: (Int) -> Unit
) {
    companion object {
        const val IDLE = TelephonyManager.CALL_STATE_IDLE
        const val RINGING = TelephonyManager.CALL_STATE_RINGING
        const val OFFHOOK = TelephonyManager.CALL_STATE_OFFHOOK
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var callback: TelephonyCallback? = null

    fun register() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val cb = object : TelephonyCallback(),
            TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                onStateChanged(state)
            }
        }

        callback = cb
        telephonyManager.registerTelephonyCallback(
            context.mainExecutor,
            cb
        )
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        }
        callback = null
    }
}
