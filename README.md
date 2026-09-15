# Handover Me

## Secure Temporary Calling Mode for Android

**Handover Me** is a native Android application designed for one simple problem:

> **Let someone borrow your phone temporarily to make a call without giving them access to the rest of the phone.**

The owner activates Handover Me's **Secure Lending Mode**, hands the device to another person, and the borrower receives a purpose-built calling experience. The borrower can dial a number and use the controlled Handover Me call screen, while the normal phone environment remains unavailable. The owner exits the session using a separate Handover Me Secure PIN.

This repository contains the **v5.3.3 core production candidate** of the Handover Me architecture.

---

## 1. Core Product Idea

Handover Me is intentionally not a general-purpose phone replacement. Its purpose is temporary, controlled phone lending.

### Normal phone

```text
Owner's Phone
   |
   +-- Apps
   +-- Photos
   +-- Messages
   +-- Settings
   +-- Accounts
   +-- Files
   +-- Normal Phone app
```

### During Handover Me Secure Lending Mode

```text
Owner
  |
  v
Handover Me
  |
  +--> Owner authentication
  |
  +--> Secure PIN
  |
  +--> Activate Secure Lending Mode
          |
          v
     Secure Dialer
          |
          +--> Dial number
          |
          +--> Cellular call
          |
          +--> Controlled call UI
          |
          +--> Owner Unlock
                    |
                    v
              Normal phone restored
```

The borrower does **not** receive a normal unrestricted phone session. The goal is to expose only the functionality Handover Me deliberately provides.

---

## 2. User Flow

### Step 1 — Owner opens Handover Me

The owner launches the app and reaches the Handover Me onboarding/owner experience.

### Step 2 — Owner authentication

Before creating or changing the Handover Me Secure PIN, the owner is authenticated through Android's biometric/device-credential system.

Handover Me does **not** read or store the Android lock-screen PIN/password.

### Step 3 — Create the separate Secure PIN

The owner creates a dedicated Handover Me PIN.

- 4–12 digits
- Stored as a salted SHA-256 verifier
- The original PIN is not stored in plaintext
- It is separate from the Android screen-lock credential

### Step 4 — Activate Secure Lending Mode

The owner taps **Activate Secure Lending Mode**.

Handover Me prepares the calling environment and enters its secure dialer.

### Step 5 — Hand the phone to the borrower

The borrower sees only the Handover Me Secure Dialer.

The borrower can:

- Enter digits 0–9
- Delete the last digit
- Start a cellular call
- Use the controlled Handover Me call screen
- End the call

The secure dialer intentionally does not provide `*` or `#` dialing buttons. The bottom-right lock control is used for **Owner Unlock** rather than dialing `#`.

### Step 6 — Cellular call

Handover Me places the cellular call through Android Telecom.

The call UI is state-aware.

**Before the call is answered:**

- `Calling…`
- Number
- Caller icon
- End Call

**After the call becomes active:**

- Mute
- Speaker
- End Call

The Handover Me call UI intentionally does not expose:

- In-call keypad
- Add Call
- More menu
- Other stock Phone UI controls

### Step 7 — Owner unlocks

When the phone is returned, the owner taps the lock/Owner Unlock control.

The owner enters the separate Handover Me Secure PIN.

If the PIN is correct, Secure Lending Mode exits.

### Step 8 — Normal phone restoration

Handover Me clears its secure/kiosk state and requests restoration of the previously recorded default Phone/Dialer application.

Android may display a system confirmation because a third-party application is not allowed to silently assign the Dialer role to another application.

---

## 3. Security Model

Handover Me uses several Android platform mechanisms together. Each mechanism solves a different part of the problem.

### 3.1 Lock Task / kiosk boundary

The kiosk layer prevents the borrower from simply leaving Handover Me through normal Android navigation when the application is operating as a properly managed Device Owner.

During the secure session the intended behavior is:

- Home cannot be used to escape
- Recent-app switching cannot be used to escape
- Other applications cannot be opened
- Normal Settings access is blocked by the managed-device boundary
- The borrower remains inside the Handover Me experience

The application uses `KioskManager` to configure and start Lock Task mode.

### 3.2 Device Owner requirement

A true unescapable Android kiosk is a platform-level security boundary. Android does not allow an ordinary sideloaded application to silently make itself Device Owner on an already-configured personal phone.

Therefore, the **security guarantee depends on supported Device Owner / managed-device provisioning**.

This is an Android platform restriction, not an application bug.

For a real managed deployment, the device must be provisioned through Android's supported device-management process before relying on Device Owner as the kiosk security boundary.

The product UX itself remains simple; the provisioning mechanism is an implementation/deployment requirement.

### 3.3 Persistent HOME behavior

When Device Owner is available, Handover Me can configure itself as the persistent HOME activity for the secure session.

This is used together with Lock Task so reboot/re-entry does not simply return the user to the normal launcher while Secure Lending Mode is supposed to remain active.

When the owner exits Secure Mode, the persistent HOME assignment is cleared.

### 3.4 Secure PIN storage

`PinStore.kt` stores:

```text
random 32-byte salt
        +
PIN bytes
        |
        v
SHA-256
        |
        v
stored verifier
```

The PIN itself is never stored in plaintext.

Verification uses `MessageDigest.isEqual()` to compare the calculated verifier with the stored verifier.

### 3.5 Owner authentication

The Android device credential/biometric prompt is used to authenticate the owner before Secure PIN creation/change.

The two credentials have different purposes:

```text
Android device credential
        |
        +--> Proves owner identity to Android

Handover Me Secure PIN
        |
        +--> Exits Handover Me Secure Lending Mode
```

Handover Me never receives the Android PIN/password itself.

---

## 4. Android Telecom Architecture

The cellular call implementation is based on Android's Telecom framework.

### Main components

```text
MainActivity
    |
    +--> Secure Dialer UI
    |
    +--> TelecomManager.placeCall()
    |
    v
Android Telecom
    |
    v
HandoverInCallService
    |
    +--> Call state callbacks
    +--> Answer
    +--> Disconnect / End Call
    +--> Mute
    +--> Speaker audio route
    |
    v
MainActivity call UI
```

### `MainActivity.kt`

The main activity controls the application state and screens:

- Splash/onboarding
- Owner authentication
- Secure PIN setup
- Secure PIN confirmation
- Owner Home
- Secure Dialer
- Calling screen
- Call-ended screen
- Owner Unlock / exit PIN

It also handles the transition between the owner, dialer, and call states.

### `HandoverInCallService.kt`

This is the Telecom integration point.

It tracks the current `Call` and exposes controlled operations:

- Answer current call
- Disconnect current call
- Set mute state
- Set speaker/audio route

It also informs `MainActivity` about Telecom call-state changes.

### `CallStateManager.kt`

This component listens for Android telephony call-state changes on supported Android versions and helps keep the UI synchronized with the cellular call state.

### Why `InCallService` is important

Launching the stock Phone activity would expose the normal phone interface. Handover Me instead uses Android Telecom's `InCallService` architecture so it can present its own controlled in-call experience when the application has the required dialer role/Telecom conditions.

---

## 5. Default Dialer Handling

Handover Me needs to participate in Android's calling stack so it can provide its controlled call UI.

Before taking the default Dialer role, the app records the current default dialer package when available.

Conceptually:

```text
Previous default Phone app
          |
          | save package name
          v
Handover Me becomes default dialer
          |
          v
Secure Lending Mode
          |
          v
Owner unlocks
          |
          v
Request restoration of previous Phone app
```

Android controls the final Dialer-role assignment. Handover Me cannot silently force another third-party package to become the default dialer.

---

## 6. Reboot Recovery

Secure-session state is persisted in Android device-protected storage.

When Secure Lending Mode is active, the app records:

```text
secure_active = true
```

The `BootReceiver` checks this state after boot.

### Managed-device path

If the application is actually Device Owner and the secure state is still active, Handover Me can restore the secure experience after reboot.

The persistent HOME configuration also supports the managed-device kiosk boundary.

### Ordinary personal-phone path

If the application is not Device Owner, Handover Me does not pretend that a true persistent kiosk exists. A stale secure flag is cleared rather than unexpectedly forcing a normal installation into Secure Dialer after reboot.

This distinction is intentional and prevents false security assumptions.

---

## 7. Application Components

```text
app/
└── src/main/
    ├── AndroidManifest.xml
    │
    ├── java/com/handoverme/app/
    │   ├── MainActivity.kt
    │   ├── KioskManager.kt
    │   ├── HandoverInCallService.kt
    │   ├── CallStateManager.kt
    │   ├── PinStore.kt
    │   ├── BootReceiver.kt
    │   └── HandoverDeviceAdminReceiver.kt
    │
    └── res/
        ├── drawable/
        │   ├── Handover Me branding
        │   ├── security icons
        │   ├── dialer backgrounds
        │   └── UI shape resources
        ├── layout/
        │   └── activity_main.xml
        ├── values/
        │   ├── colors.xml
        │   ├── strings.xml
        │   └── themes.xml
        └── xml/
            └── device_admin_receiver.xml
```

### Component responsibilities

| Component | Responsibility |
|---|---|
| `MainActivity` | Main UI, owner flow, dialer flow, call flow, PIN entry and transitions |
| `KioskManager` | Device Owner checks, Lock Task configuration, secure kiosk state |
| `HandoverInCallService` | Android Telecom in-call integration and call controls |
| `CallStateManager` | Telephony state observation |
| `PinStore` | Salted Secure PIN creation and verification |
| `BootReceiver` | Secure-session reboot recovery |
| `HandoverDeviceAdminReceiver` | Device Administration receiver required by the managed-device architecture |
| `activity_main.xml` | Main multi-screen XML UI |
| Drawable resources | Handover Me visual identity and controls |

---

## 8. Manifest / Permissions

The application declares permissions required by the architecture, including:

- `CALL_PHONE` — placing cellular calls
- `READ_PHONE_STATE` — reading permitted phone-state information
- `READ_PHONE_NUMBERS` — permitted phone-number related functionality
- `RECEIVE_BOOT_COMPLETED` — reboot recovery
- Device administration binding — managed-device administration integration
- `READ_CALL_LOG` / `WRITE_CALL_LOG` — calling-stack functionality where permitted by the Android/device environment
- `BIND_INCALL_SERVICE` — Android Telecom in-call service binding

Android runtime permissions and role/device-management requirements still apply. Declaring a permission in the manifest does not automatically grant it.

---

## 9. UI Design

Handover Me uses a dark, security-focused visual language.

### Design characteristics

- True black / blue-black base
- Electric blue and cyan Handover Me branding
- Neon green action emphasis
- Red security-state emphasis
- Rounded cards and controls
- Dedicated security status indicator
- Handover Me shield/phone logo
- Owner-only lock/unlock affordance

The first owner-facing screens include the project credit:

**Powered by Sushanth Chithaluri**

The secure dialer is deliberately simplified so a borrower is not presented with a conventional full-featured phone application.

---

## 10. Secure Dialer Behavior

The secure dialer contains:

```text
          SECURE MODE ACTIVE 🔒

             1234567890

        [ 1 ] [ 2 ] [ 3 ]
        [ 4 ] [ 5 ] [ 6 ]
        [ 7 ] [ 8 ] [ 9 ]
        [ Backspace ] [ 0 ] [ Owner Unlock ]

                 CALL
```

The exact visual spacing is controlled by the XML layout and Android device density.

The design intentionally avoids a normal `*` and `#` keypad. `#` is represented by the owner-unlock action rather than exposed as a dialing character.

---

## 11. Call State Machine

The important application states are:

```text
OWNER
  |
  | Activate
  v
DIALER
  |
  | Place cellular call
  v
CALL / CALLING
  |
  | Telecom reports ACTIVE
  v
ACTIVE CALL
  |
  | Telecom reports DISCONNECTED
  v
CALL ENDED
  |
  v
DIALER
```

The UI deliberately does not show active-call controls while the call is still connecting.

### Calling state

```text
Calling…
Number
Caller icon

End Call
```

### Active state

```text
Mute       Speaker       End Call
```

This prevents the UI from incorrectly presenting Mute/Speaker controls before Telecom has reported that the call is active.

---

## 12. Owner Unlock Flow

The Owner Unlock path is intentionally separate from normal borrower dialing.

```text
Secure Dialer
     |
     v
Owner Unlock
     |
     v
Enter Secure PIN
     |
     +---- incorrect --> remain locked / attempt counted
     |
     +---- correct ----> Exit Secure Mode
                              |
                              v
                       Restore normal phone
```

The current implementation limits PIN verification attempts per session to reduce uncontrolled guessing.

---

## 13. Build Configuration

Current project configuration:

- **compileSdk:** 35
- **targetSdk:** 35
- **minSdk:** 28 (Android 9)
- **Java target:** 17
- **Kotlin JVM target:** 17
- **AndroidX Biometric:** 1.1.0

The project also disables the Kotlin daemon and uses in-process Kotlin compilation to avoid the JVM/daemon instability encountered during development.

### Open in Android Studio

1. Extract the project ZIP.
2. Open the `HandoverMe-CellularKiosk` folder in Android Studio.
3. Allow Gradle synchronization to complete.
4. Ensure Android SDK 35 is installed.
5. Use a Java 17-compatible Gradle JDK.
6. Build with **Build → Rebuild Project**.
7. Install the debug APK on the test device.

The repository intentionally does not include a Gradle wrapper; Android Studio/system Gradle is used for the project.

---

## 14. Testing on a Real Device

Real-device testing is important because Telecom, Dialer role behavior, Lock Task, device-management policies, permissions, and OEM customization can differ between devices.

### Basic test checklist

#### Owner setup

- [ ] App opens normally
- [ ] Owner authentication works
- [ ] Secure PIN can be created
- [ ] Secure PIN confirmation works
- [ ] Secure PIN change requires owner authentication

#### Secure Mode

- [ ] Activate Secure Lending Mode
- [ ] Secure Dialer appears
- [ ] Home cannot escape the secure experience when the managed kiosk boundary is active
- [ ] Recent apps cannot be used to escape
- [ ] Other apps cannot be opened
- [ ] Settings cannot be reached through normal navigation
- [ ] Owner Unlock remains available

#### Calling

- [ ] Digits can be entered
- [ ] Backspace works
- [ ] Call can be placed
- [ ] Connecting state shows `Calling…`
- [ ] Mute/Speaker do not appear before the call is active
- [ ] Active call shows Mute, Speaker and End Call
- [ ] Mute works
- [ ] Speaker works
- [ ] End Call works
- [ ] Call-ended screen returns to the secure dialer

#### Exit

- [ ] Owner Unlock opens PIN entry
- [ ] Incorrect PIN does not exit Secure Mode
- [ ] Correct PIN exits Secure Mode
- [ ] Kiosk state is cleared
- [ ] Previous/default Phone app restoration is requested
- [ ] Any Android system confirmation is handled correctly

#### Reboot

- [ ] Secure-state behavior is correct after reboot on a managed Device Owner device
- [ ] Normal personal-phone installation does not use a stale secure flag to unexpectedly force Secure Dialer

---

## 15. Important Platform Limitations

Handover Me is built around Android platform APIs, so some guarantees depend on Android's device-management and Telecom rules.

### Device Owner cannot be self-granted

A normal application cannot silently turn an already-configured personal phone into a Device Owner-managed device.

For a true kiosk boundary, the device must be provisioned through Android's supported managed-device flow.

### Default Dialer role is controlled by Android

Android controls who can hold the default Dialer role. Handover Me can request the role, but cannot silently assign a different third-party Phone application when exiting.

### OEM behavior can differ

Samsung, Xiaomi, OnePlus, Pixel, Motorola, and other manufacturers may apply different system policies around permissions, power management, Telecom, and device management.

Therefore, a production rollout should validate the exact target device fleet.

### Android version differences

The minimum project SDK is Android 9 (API 28). Some Android Telecom and role APIs have different requirements across Android releases. The application must be tested on the Android versions and device models that the deployment actually supports.

---

## 16. Why This Architecture Is Used

The project looks simple from the user's perspective, but Android separates the required responsibilities across several system services.

```text
                     Handover Me
                         |
          +--------------+--------------+
          |              |              |
          v              v              v
     Android Auth    Android Telecom   Android Device Policy
          |              |              |
          v              v              v
     Owner proof     Cellular calls   Kiosk boundary
          |              |              |
          +--------------+--------------+
                         |
                         v
                Secure Lending Mode
```

The complexity is therefore implementation-level complexity, not product-level complexity.

The user experience remains one simple promise:

> **Hand over your phone for a call without handing over your phone.**

---

## 17. Security Boundaries vs. App Features

It is important to distinguish what Handover Me controls directly from what Android controls.

### Handover Me controls

- Secure PIN
- Owner authentication flow
- Dialer UI
- Call UI
- Available call controls
- Secure-session state
- Owner unlock flow
- Default-dialer restoration request
- Application visual design

### Android controls

- Device Owner provisioning
- Lock Task enforcement
- Default Dialer role assignment
- Runtime permission grants
- Telecom call lifecycle
- Device/OEM restrictions
- System-level security UI

This separation is intentional. Handover Me uses Android's supported security primitives instead of attempting to bypass the operating system.

---

## 18. Project Status

### Version: v5.3.3

**Status: Core production candidate**

The v5.3.3 architecture is focused on the final core product idea rather than adding unnecessary enterprise-facing features.

The project should be treated as production-ready **only after the final target-device test matrix has passed**, especially for the exact Android versions, OEM devices, SIM/cellular configuration, Dialer role behavior, and managed-device provisioning used in deployment.

The later managed-device onboarding experiments are separate deployment work and are not required to understand the core Handover Me product architecture.

---

## 19. Future Improvements

Possible future work can be added without changing the core concept:

- More robust automated device/OEM compatibility testing
- Improved accessibility
- Localization
- Better error/recovery messaging
- Secure audit/event logging without exposing private call content
- Automated release builds and CI
- Automated UI/instrumentation tests
- Additional supported Android versions/devices

These are engineering improvements, not changes to the central product idea.

---

## 20. License

Add the project's chosen license here before publishing the repository publicly.

---

## Summary

**Handover Me is a secure temporary calling mode for Android.**

It allows a phone owner to temporarily hand the device to another person for a cellular call while keeping the borrower inside a controlled Handover Me experience.

The complete concept is:

```text
OWNER
  |
  v
Authenticate
  |
  v
Secure PIN
  |
  v
Activate Secure Lending Mode
  |
  v
HAND PHONE TO BORROWER
  |
  v
Secure Dialer
  |
  v
Cellular Call
  |
  v
Controlled Call UI
  |
  v
OWNER TAKES PHONE BACK
  |
  v
Secure PIN
  |
  v
Exit Secure Mode
  |
  v
NORMAL PHONE
```

**The goal is simple: lend your phone for a call without giving away access to your phone.**
"# HandoverMe" 
"# HandoverMe" 
