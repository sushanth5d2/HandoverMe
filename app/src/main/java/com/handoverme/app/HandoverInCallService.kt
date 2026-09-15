package com.handoverme.app

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Handover Me's real Telecom UI controller.
 * This replaces the stock in-call screen when Handover Me is the default dialer.
 */
class HandoverInCallService : InCallService() {
    companion object {
        private var instance: HandoverInCallService? = null
        private var currentCall: Call? = null

        fun disconnectCurrentCall() { currentCall?.disconnect() }
        fun answerCurrentCall() { currentCall?.answer(0) }
        fun setMuted(muted: Boolean) { instance?.setMuted(muted) }
        fun setSpeaker(enabled: Boolean) {
            instance?.setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
        }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val number = call.details?.handle?.schemeSpecificPart
            when (state) {
                Call.STATE_DIALING, Call.STATE_CONNECTING -> notifyUi("Calling…", number)
                Call.STATE_ACTIVE -> notifyUi("Active Call", number)
                Call.STATE_DISCONNECTED -> notifyUi("Call Ended", number)
                Call.STATE_RINGING -> notifyUi("Incoming Call", number)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callback)
        val number = call.details?.handle?.schemeSpecificPart
        val state = call.state
        val label = when (state) {
            Call.STATE_ACTIVE -> "Active Call"
            Call.STATE_RINGING -> "Incoming Call"
            else -> "Calling…"
        }
        notifyUi(label, number)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (currentCall == call) currentCall = null
        notifyUi("Call Ended", call.details?.handle?.schemeSpecificPart)
        super.onCallRemoved(call)
    }

    private fun notifyUi(status: String, number: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("show_call", true)
            putExtra("call_number", number)
            putExtra("call_status", status)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try { startActivity(intent) } catch (_: Exception) { }
        MainActivity.current?.updateCallUiFromTelecom(status, number)
    }
}
