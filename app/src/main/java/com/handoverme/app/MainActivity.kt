package com.handoverme.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import android.app.role.RoleManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        var current: MainActivity? = null
        private const val OWNER = 0
        private const val DIALER = 1
        private const val CALL = 2
        private const val MAX_NUMBER_LENGTH = 15
        private const val MAX_PIN_LENGTH = 12
        private const val CALL_PERMISSION_REQUEST = 1001
        private const val MAX_PIN_ATTEMPTS = 5
        private const val REQUEST_DEFAULT_DIALER = 2101
    }

    private lateinit var splashView: View
    private lateinit var authView: View
    private lateinit var pinSetupView: View
    private lateinit var pinConfirmView: View
    private lateinit var pinSetView: View
    private lateinit var ownerView: View
    private lateinit var dialerView: View
    private lateinit var callView: View
    private lateinit var callEndedView: View
    private lateinit var exitPinView: View

    private lateinit var dialDisplay: TextView
    private lateinit var callNumber: TextView
    private lateinit var callStatus: TextView
    private lateinit var callDuration: TextView
    private lateinit var btnBackspace: ImageButton
    private lateinit var btnExitLock: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnAnswerCall: View

    private lateinit var pinStore: PinStore
    private lateinit var kiosk: KioskManager
    private lateinit var callStateManager: CallStateManager
    private lateinit var audioManager: AudioManager

    private var state = OWNER
    private var pinAttempts = 0
    private val dialNumber = StringBuilder()
    private var newPin = ""
    private var setupPin = StringBuilder()
    private var confirmPin = StringBuilder()
    private var exitPin = StringBuilder()
    private var muted = false
    private var speakerOn = false
    private var callStartedAt = 0L
    private var activationPending = false
    private val handler = Handler(Looper.getMainLooper())
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (state == CALL && callStartedAt > 0) {
                val seconds = (System.currentTimeMillis() - callStartedAt) / 1000
                callDuration.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        current = this

        pinStore = PinStore(this)
        kiosk = KioskManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initializeViews()
        initializeDialPad()
        initializePinPads()
        initializeBackHandling()

        callStateManager = CallStateManager(this) { callState -> handleCellularCallState(callState) }

        val securePrefs = createDeviceProtectedStorageContext().getSharedPreferences("handover_state", Context.MODE_PRIVATE)
        val restoreSecure = savedInstanceState?.getBoolean("secure_active") == true ||
                intent.getBooleanExtra("restore_secure", false) ||
                securePrefs.getBoolean("secure_active", false)
        if (restoreSecure && pinStore.hasPin()) {
            enterSecureMode()
        } else {
            showSplashScreen()
        }
        if (intent.getBooleanExtra("show_call", false)) {
            showCallFromTelecom(intent.getStringExtra("call_number"))
        }
    }

    private fun initializeViews() {
        splashView = findViewById(R.id.splashView)
        authView = findViewById(R.id.authView)
        pinSetupView = findViewById(R.id.pinSetupView)
        pinConfirmView = findViewById(R.id.pinConfirmView)
        pinSetView = findViewById(R.id.pinSetView)
        ownerView = findViewById(R.id.ownerView)
        dialerView = findViewById(R.id.dialerView)
        callView = findViewById(R.id.callView)
        callEndedView = findViewById(R.id.callEndedView)
        exitPinView = findViewById(R.id.exitPinView)
        dialDisplay = findViewById(R.id.dialDisplay)
        callNumber = findViewById(R.id.callNumber)
        callStatus = findViewById(R.id.callStatusTop)
        callDuration = findViewById(R.id.callDuration)
        btnBackspace = findViewById(R.id.btnBackspace)
        btnExitLock = findViewById(R.id.btnExitLock)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnAnswerCall = findViewById(R.id.btnAnswerCall)

        findViewById<View>(R.id.btnGetStarted).setOnClickListener {
            if (pinStore.hasPin()) showOwnerScreen() else showOwnerAuthentication()
        }
        findViewById<View>(R.id.btnAuthenticate).setOnClickListener {
            authenticateOwnerForPinChange { showPinSetupScreen() }
        }
        findViewById<View>(R.id.btnFaceUnlock).setOnClickListener {
            authenticateOwnerWithBiometric(BiometricManager.Authenticators.BIOMETRIC_WEAK) {
                showPinSetupScreen()
            }
        }
        findViewById<View>(R.id.btnDeviceCredential).setOnClickListener {
            authenticateOwnerWithDeviceCredential {
                showPinSetupScreen()
            }
        }
        findViewById<View>(R.id.btnAuthBack).setOnClickListener { showSplashThenHome(false) }
        findViewById<View>(R.id.btnPinBack).setOnClickListener { showOwnerAuthentication() }
        findViewById<View>(R.id.btnConfirmBack).setOnClickListener { showPinSetupScreen() }
        findViewById<View>(R.id.btnGoHome).setOnClickListener { showOwnerScreen() }
        findViewById<View>(R.id.btnActivate).setOnClickListener {
            if (!pinStore.hasPin()) showOwnerAuthentication() else requestDefaultDialerAndActivate()
        }
        findViewById<View>(R.id.btnSetPin).setOnClickListener { showOwnerAuthentication() }
        findViewById<View>(R.id.btnSecurePinRow).setOnClickListener { showOwnerAuthentication() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<View>(R.id.btnHow).setOnClickListener {
            Toast.makeText(this, "Secure Lending Mode restricts the device to Handover Me until the separate Secure PIN is entered.", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btnAbout).setOnClickListener {
            Toast.makeText(this, "Handover Me • Secure phone lending", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnCall).setOnClickListener { placeCellularCall() }
        btnBackspace.setOnClickListener { deleteLastDigit() }
        btnExitLock.setOnClickListener { showExitPinScreen() }
        findViewById<View>(R.id.btnExitBack).setOnClickListener { showDialerScreen() }
        findViewById<View>(R.id.btnEndCall).setOnClickListener { endCellularCall() }
        btnAnswerCall.setOnClickListener { HandoverInCallService.answerCurrentCall() }
        findViewById<View>(R.id.btnBackToDialer).setOnClickListener { showDialerScreen() }
        btnMute.setOnClickListener { toggleMute() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
    }


    private fun showSettingsDialog() {
        val status = when {
            kiosk.isDeviceOwner && isDefaultDialer() -> "Protected • Default dialer • Device Owner"
            isDefaultDialer() -> "Default dialer • Device Owner setup required for full kiosk"
            else -> "Setup required • Handover Me is not the default dialer"
        }
        AlertDialog.Builder(this)
            .setTitle("Handover Me Settings")
            .setMessage("Security status\n$status\n\nSecure PIN\nConfigured\n\nHandover Me uses a separate Secure PIN for owner unlock.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun isDefaultDialer(): Boolean {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false
        return packageName == telecom.defaultDialerPackage
    }
    private fun initializeDialPad() {
        mapOf(
            R.id.btn1 to "1", R.id.btn2 to "2", R.id.btn3 to "3",
            R.id.btn4 to "4", R.id.btn5 to "5", R.id.btn6 to "6",
            R.id.btn7 to "7", R.id.btn8 to "8", R.id.btn9 to "9", R.id.btn0 to "0"
        ).forEach { (id, digit) -> findViewById<TextView>(id).setOnClickListener { appendDigit(digit) } }
    }

    private fun initializePinPads() {
        val setupMap = mapOf(R.id.pin1 to "1", R.id.pin2 to "2", R.id.pin3 to "3", R.id.pin4 to "4", R.id.pin5 to "5", R.id.pin6 to "6", R.id.pin7 to "7", R.id.pin8 to "8", R.id.pin9 to "9", R.id.pin0 to "0")
        setupMap.forEach { (id, d) -> findViewById<TextView>(id).setOnClickListener { pinDigit(setupPin, d, findViewById(R.id.pinDots)) } }
        findViewById<View>(R.id.pinDelete).setOnClickListener { pinDelete(setupPin, findViewById(R.id.pinDots)) }
        findViewById<View>(R.id.pinNext).setOnClickListener { finishSetupPinEntry() }

        val confirmMap = mapOf(R.id.cpin1 to "1", R.id.cpin2 to "2", R.id.cpin3 to "3", R.id.cpin4 to "4", R.id.cpin5 to "5", R.id.cpin6 to "6", R.id.cpin7 to "7", R.id.cpin8 to "8", R.id.cpin9 to "9", R.id.cpin0 to "0")
        confirmMap.forEach { (id, d) -> findViewById<TextView>(id).setOnClickListener { pinDigit(confirmPin, d, findViewById(R.id.confirmDots)) } }
        findViewById<View>(R.id.cpinDelete).setOnClickListener { pinDelete(confirmPin, findViewById(R.id.confirmDots)) }
        findViewById<View>(R.id.cpinDone).setOnClickListener { finishConfirmPinEntry() }

        val exitMap = mapOf(R.id.epin1 to "1", R.id.epin2 to "2", R.id.epin3 to "3", R.id.epin4 to "4", R.id.epin5 to "5", R.id.epin6 to "6", R.id.epin7 to "7", R.id.epin8 to "8", R.id.epin9 to "9", R.id.epin0 to "0")
        exitMap.forEach { (id, d) -> findViewById<TextView>(id).setOnClickListener { pinDigit(exitPin, d, findViewById(R.id.exitDots)) } }
        findViewById<View>(R.id.epinDelete).setOnClickListener { pinDelete(exitPin, findViewById(R.id.exitDots)) }
        findViewById<View>(R.id.epinDone).setOnClickListener { verifyExitPin() }
    }

    private fun pinDigit(buffer: StringBuilder, digit: String, dots: TextView) {
        if (buffer.length >= MAX_PIN_LENGTH) return
        buffer.append(digit)
        updateDots(dots, buffer.length)
    }

    private fun pinDelete(buffer: StringBuilder, dots: TextView) {
        if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.lastIndex)
        updateDots(dots, buffer.length)
    }

    private fun updateDots(view: TextView, length: Int) {
        val count = maxOf(4, minOf(MAX_PIN_LENGTH, length))
        val visible = "●  ".repeat(length.coerceAtMost(8)).trim()
        val empty = if (length < 4) "  " + "○  ".repeat(4 - length).trim() else ""
        view.text = if (length == 0) "○  ○  ○  ○" else visible + if (length < 4) "  " + "○  ".repeat(4 - length).trim() else ""
    }

    private fun finishSetupPinEntry() {
        if (setupPin.length !in 4..MAX_PIN_LENGTH) {
            Toast.makeText(this, "Use 4–12 digits.", Toast.LENGTH_SHORT).show(); return
        }
        newPin = setupPin.toString()
        confirmPin.clear(); updateDots(findViewById(R.id.confirmDots), 0)
        showOnly(pinConfirmView); showSystemBars()
    }

    private fun finishConfirmPinEntry() {
        if (confirmPin.toString() != newPin) {
            Toast.makeText(this, "PINs do not match. Try again.", Toast.LENGTH_SHORT).show()
            confirmPin.clear(); updateDots(findViewById(R.id.confirmDots), 0); return
        }
        pinStore.setPin(newPin)
        setupPin.clear(); confirmPin.clear(); newPin = ""
        showOnly(pinSetView); showSystemBars()
    }

    private fun initializeBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (state == OWNER) finish()
            }
        })
    }

    private fun showSplashScreen() {
        showOnly(splashView)
        showSystemBars()
    }

    private fun showSplashThenHome(showSplash: Boolean = true) {
        if (showSplash) showSplashScreen() else showOwnerScreen()
    }

    private fun showOwnerAuthentication() {
        showOnly(authView); showSystemBars()
    }

    private fun showPinSetupScreen() {
        setupPin.clear(); updateDots(findViewById(R.id.pinDots), 0)
        showOnly(pinSetupView); showSystemBars()
    }

    private fun showOwnerScreen() {
        state = OWNER
        showOnly(ownerView); showSystemBars()
    }

    private fun showDialerScreen() {
        state = DIALER
        showOnly(dialerView); hideSystemBars()
    }

    private fun showCallScreen() {
        state = CALL
        showOnly(callView); hideSystemBars()

        val active = callStatus.text?.toString() == "Active Call"
        // During Calling: show only Speaker. During Active Call: show Mute + Speaker.
        findViewById<View>(R.id.callControls).visibility = View.VISIBLE
        findViewById<View>(R.id.muteContainer).visibility = if (active) View.VISIBLE else View.GONE
        findViewById<View>(R.id.speakerContainer).visibility = View.VISIBLE

        if (active) {
            if (callStartedAt == 0L) callStartedAt = System.currentTimeMillis()
            handler.removeCallbacks(durationRunnable)
            handler.post(durationRunnable)
        } else {
            callStartedAt = 0L
            callDuration.text = ""
            handler.removeCallbacks(durationRunnable)
        }
    }

    private fun showCallEndedScreen() {
        state = CALL
        handler.removeCallbacks(durationRunnable)
        showOnly(callEndedView); hideSystemBars()
    }

    private fun showExitPinScreen() {
        if (state == OWNER) return
        exitPin.clear(); updateDots(findViewById(R.id.exitDots), 0)
        findViewById<TextView>(R.id.exitError).text = ""
        showOnly(exitPinView); hideSystemBars()
    }

    private fun showOnly(target: View) {
        listOf(splashView, authView, pinSetupView, pinConfirmView, pinSetView, ownerView, dialerView, callView, callEndedView, exitPinView).forEach { it.visibility = if (it === target) View.VISIBLE else View.GONE }
    }

    private fun authenticateOwnerWithBiometric(
        authenticator: Int,
        onAuthenticated: () -> Unit
    ) {
        if (BiometricManager.from(this).canAuthenticate(authenticator) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "No supported biometric is enrolled on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Face Unlock")
            .setSubtitle("Verify your identity using an enrolled biometric")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(authenticator)
            .build()
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(this@MainActivity, "Owner authentication cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
        ).authenticate(info)
    }

    private fun authenticateOwnerWithDeviceCredential(onAuthenticated: () -> Unit) {
        if (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "No phone PIN, pattern, or password is configured.", Toast.LENGTH_LONG).show()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Owner Authentication")
            .setSubtitle("Enter your phone PIN, pattern, or password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(this@MainActivity, "Owner authentication cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
        ).authenticate(info)
    }

    private fun authenticateOwnerForPinChange(onAuthenticated: () -> Unit) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Set a phone PIN, pattern, password, or supported biometric first.", Toast.LENGTH_LONG).show(); return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Owner Authentication")
            .setSubtitle("Verify your phone's screen lock to manage the Secure PIN")
            .setAllowedAuthenticators(authenticators)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onAuthenticated() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { Toast.makeText(this@MainActivity, "Owner authentication cancelled.", Toast.LENGTH_SHORT).show() }
        }).authenticate(info)
    }

    /**
     * Handles the device-level telephony callback. The Telecom InCallService is
     * the primary source for the custom call UI; this callback is kept as a
     * fallback so the activity also reacts correctly to cellular state changes.
     */
    private fun handleCellularCallState(callState: Int) {
        if (state == OWNER) return

        runOnUiThread {
            when (callState) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    callStatus.text = "Incoming Call"
                    if (state != CALL) showCallScreen()
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    callStatus.text = if (callStatus.text?.toString() == "Incoming Call") {
                        "Active Call"
                    } else {
                        "Calling…"
                    }
                    if (state != CALL) showCallScreen()
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    // InCallService normally reports the end of a call. Do not
                    // replace its UI immediately; only finish the timer here.
                    if (state == CALL) {
                        handler.removeCallbacks(durationRunnable)
                    }
                }
            }
        }
    }

    private fun enterSecureMode() {
        // A configured Secure PIN is mandatory before lending mode can start.
        // The dialer UI must still open on a normal test phone; Device Owner is
        // required only for the real Android kiosk boundary.
        if (!pinStore.hasPin()) {
            showOwnerAuthentication()
            return
        }

        dialNumber.clear()
        updateDialDisplay()

        // Persist BEFORE entering kiosk. If Android kills/restarts the
        // activity during transition, boot recovery still knows that Secure
        // Lending Mode must be restored.
        createDeviceProtectedStorageContext()
            .getSharedPreferences("handover_state", Context.MODE_PRIVATE)
            .edit().putBoolean("secure_active", true).commit()

        val kioskStarted = try {
            kiosk.enter(this)
        } catch (_: Exception) {
            false
        }

        showDialerScreen()
        callStateManager.register()

    }

    private fun appendDigit(digit: String) {
        if (state != DIALER || dialNumber.length >= MAX_NUMBER_LENGTH || !digit.matches(Regex("^[0-9]$"))) return
        dialNumber.append(digit); updateDialDisplay()
    }

    private fun deleteLastDigit() { if (state == DIALER && dialNumber.isNotEmpty()) { dialNumber.deleteCharAt(dialNumber.lastIndex); updateDialDisplay() } }

    private fun updateDialDisplay() {
        val clean = dialNumber.toString().filter { it in '0'..'9' }.take(MAX_NUMBER_LENGTH)
        dialNumber.clear(); dialNumber.append(clean)
        dialDisplay.text = if (clean.isEmpty()) "Enter number" else formatPhoneSpaces(clean)
    }

    private fun formatPhoneSpaces(number: String): String {
        // Format as "XXXX XXX XXX" pattern for readability
        val sb = StringBuilder()
        for (i in number.indices) {
            if (i == 4 || i == 7) sb.append(' ')
            sb.append(number[i])
        }
        return sb.toString()
    }

    private fun finishActivation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            activationPending = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), CALL_PERMISSION_REQUEST)
            return
        }
        activationPending = false
        enterSecureMode()
    }

    private fun requestDefaultDialerAndActivate() {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecom?.defaultDialerPackage == packageName) {
            finishActivation()
            return
        }

        activationPending = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses the RoleManager dialer role.
            val roleManager = getSystemService(RoleManager::class.java)
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                REQUEST_DEFAULT_DIALER
            )
        } else {
            // Android 7-9 use the legacy Telecom default-dialer chooser.
            // This API was introduced in API 23 and is therefore available
            // for our Android 7+ minimum SDK.
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    packageName
                )
            }
            try {
                startActivityForResult(intent, REQUEST_DEFAULT_DIALER)
            } catch (_: Exception) {
                activationPending = false
                Toast.makeText(
                    this,
                    "Please set Handover Me as the default Phone app in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun placeCellularCall() {
        if (state != DIALER) return
        if (dialNumber.length < 3) { Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show(); return }
        // Verify the actual Telecom default-dialer package on every Android
        // version. Android 10+ exposes the same state through RoleManager, but
        // TelecomManager.getDefaultDialerPackage() also works on Android 7-9.
        if (!isDefaultDialer()) {
            Toast.makeText(this, "Handover Me must be the default phone app for Secure Calls.", Toast.LENGTH_LONG).show()
            requestDefaultDialerAndActivate()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), CALL_PERMISSION_REQUEST); return
        }
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            // TelecomManager.placeCall() is available from API 23 and is the
            // supported outgoing-call path for a dialer. Using it on every
            // supported Android version avoids ACTION_CALL differences between
            // old Android releases and current Android 16+.
            val number = dialNumber.toString().filter { it.isDigit() || it == '+' }
            val uri = Uri.fromParts("tel", number, null)

            // Android can have multiple call-capable SIM accounts. If the user
            // has not selected a default outgoing SIM, TelecomManager.placeCall()
            // may not know which subscription to use. Resolve that explicitly.
            val phoneAccount = chooseOutgoingPhoneAccount(telecom)
            if (phoneAccount == null && telecom.callCapablePhoneAccounts.size > 1 &&
                telecom.userSelectedOutgoingPhoneAccount == null) {
                return
            }
            val extras = Bundle()
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccount)
            telecom.placeCall(uri, extras)

            callNumber.text = formatPhoneSpaces(number.filter { it.isDigit() })
            callStatus.text = "Calling…"
            callDuration.text = ""
        } catch (_: SecurityException) {
            Toast.makeText(this, "Phone permission was not granted.", Toast.LENGTH_LONG).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, "Unable to place the call. Check the phone number.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            // Keep a final ACTION_CALL fallback for unusual/legacy OEM Telecom
            // implementations. The normal path above remains TelecomManager.
            try {
                val number = dialNumber.toString().filter { it.isDigit() || it == '+' }
                val callIntent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                startActivity(callIntent)
                callNumber.text = formatPhoneSpaces(number.filter { it.isDigit() })
                callStatus.text = "Calling…"
                callDuration.text = ""
            } catch (_: SecurityException) {
                Toast.makeText(this, "Phone permission was not granted.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Unable to place the call.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun chooseOutgoingPhoneAccount(telecom: TelecomManager): PhoneAccountHandle? {
        val accounts = try { telecom.callCapablePhoneAccounts } catch (_: Exception) { emptyList() }
        if (accounts.isEmpty()) return null

        // If Android already has a user-selected outgoing SIM, respect it.
        val selected = try { telecom.userSelectedOutgoingPhoneAccount } catch (_: Exception) { null }
        if (selected != null && accounts.contains(selected)) return selected
        if (accounts.size == 1) return accounts[0]

        // No default outgoing SIM: let the owner/borrower choose the SIM for
        // this call. The selected PhoneAccountHandle is then sent to Telecom.
        var selectedAccount: PhoneAccountHandle? = null
        val labels = accounts.mapIndexed { index, handle ->
            val label = try {
                telecom.getPhoneAccount(handle)?.label?.toString()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            label ?: "SIM ${index + 1}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose SIM for outgoing call")
            .setItems(labels) { _, which ->
                selectedAccount = accounts[which]
                placeCallWithPhoneAccount(selectedAccount!!)
            }
            .setOnCancelListener { }
            .show()

        // Dialog handles the actual call asynchronously. Returning null here
        // tells the caller not to place a second call.
        return null
    }

    private fun placeCallWithPhoneAccount(phoneAccount: PhoneAccountHandle) {
        val number = dialNumber.toString().filter { it.isDigit() || it == '+' }
        if (number.length < 3) return
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", number, null)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccount)
            }
            telecom.placeCall(uri, extras)
            callNumber.text = formatPhoneSpaces(number.filter { it.isDigit() })
            callStatus.text = "Calling…"
            callDuration.text = ""
        } catch (_: SecurityException) {
            Toast.makeText(this, "Phone permission was not granted.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to place the call.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCallFromTelecom(number: String?) {
        if (number != null) callNumber.text = formatPhoneSpaces(number)
        callStatus.text = intent.getStringExtra("call_status") ?: "Calling…"
        showCallScreen()
        btnAnswerCall.visibility = if (callStatus.text == "Incoming Call") View.VISIBLE else View.GONE
    }

    fun updateCallUiFromTelecom(status: String, number: String?) {
        runOnUiThread {
            if (number != null) callNumber.text = formatPhoneSpaces(number)
            callStatus.text = status
            btnAnswerCall.visibility = if (status == "Incoming Call") View.VISIBLE else View.GONE
            // Show controls: Speaker always visible during call, Mute only during Active Call
            findViewById<View>(R.id.callControls).visibility = if (status == "Call Ended") View.GONE else View.VISIBLE
            findViewById<View>(R.id.muteContainer).visibility = if (status == "Active Call") View.VISIBLE else View.GONE
            findViewById<View>(R.id.speakerContainer).visibility = if (status == "Call Ended") View.GONE else View.VISIBLE
            if (status == "Call Ended") {
                showCallEndedScreen()
                handler.postDelayed({ if (state == CALL) { dialNumber.clear(); updateDialDisplay(); showDialerScreen() } }, 1200)
            } else if (status == "Active Call") {
                if (state != CALL) showCallScreen()
                else {
                    if (callStartedAt == 0L) callStartedAt = System.currentTimeMillis()
                    handler.removeCallbacks(durationRunnable)
                    handler.post(durationRunnable)
                }
            } else if (state != CALL) {
                showCallScreen()
            }
        }
    }

    private fun endCellularCall() {
        HandoverInCallService.disconnectCurrentCall()
    }

    private fun toggleMute() {
        muted = !muted
        HandoverInCallService.setMuted(muted)
        btnMute.alpha = if (muted) 0.55f else 1f
    }

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        HandoverInCallService.setSpeaker(speakerOn)
        btnSpeaker.alpha = if (speakerOn) 0.55f else 1f
    }

    private fun openDefaultPhoneAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
            // Some device builds may not expose the Default apps settings activity.
        }
    }

    private fun verifyExitPin() {
        if (pinAttempts >= MAX_PIN_ATTEMPTS) { findViewById<TextView>(R.id.exitError).text = "Too many attempts. Secure mode remains active."; return }
        if (pinStore.verify(exitPin.toString())) {
            pinAttempts = 0
            createDeviceProtectedStorageContext().getSharedPreferences("handover_state", Context.MODE_PRIVATE)
                .edit().putBoolean("secure_active", false).apply()
            callStateManager.unregister()
            kiosk.exit(this)
            exitPin.clear()
            showOwnerScreen()
            openDefaultPhoneAppSettings()
        } else {
            pinAttempts++; exitPin.clear(); updateDots(findViewById(R.id.exitDots), 0)
            findViewById<TextView>(R.id.exitError).text = if (pinAttempts >= MAX_PIN_ATTEMPTS) "Too many attempts. Secure mode remains active." else "Incorrect PIN. Try again."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEFAULT_DIALER) {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (activationPending && telecom?.defaultDialerPackage == packageName) {
                finishActivation()
            } else {
                activationPending = false
                Toast.makeText(
                    this,
                    "Default phone app permission is required for Secure Calls.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            activationPending = false
            Toast.makeText(this, "Phone permission is required for Secure Calls.", Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode == CALL_PERMISSION_REQUEST) {
            if (activationPending) {
                activationPending = false
                enterSecureMode()
            } else {
                placeCellularCall()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            if (intent.getBooleanExtra("show_call", false)) {
                showCallFromTelecom(intent.getStringExtra("call_number"))
                val status = intent.getStringExtra("call_status")
                if (!status.isNullOrBlank()) callStatus.text = status
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("secure_active", state != OWNER); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        if (state != OWNER) {
            hideSystemBars()
            return
        }

        // Defensive recovery for activity recreation after reboot/process death.
        // Do not show Owner Home while the persisted lending session is active.
        val secureActive = createDeviceProtectedStorageContext()
            .getSharedPreferences("handover_state", Context.MODE_PRIVATE)
            .getBoolean("secure_active", false)
        if (secureActive && pinStore.hasPin()) {
            enterSecureMode()
        }
    }
    override fun onDestroy() { if (current === this) current = null; handler.removeCallbacks(durationRunnable); callStateManager.unregister(); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && state != OWNER) hideSystemBars() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { if (state != OWNER && keyCode == KeyEvent.KEYCODE_BACK) return true; return super.onKeyDown(keyCode, event) }

    private fun hideSystemBars() {
        // Match the reference UI: keep the Android status bar visible while
        // suppressing navigation controls. Device Owner + Lock Task is what
        // provides the actual kiosk boundary in production.
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()) else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
    }
}
