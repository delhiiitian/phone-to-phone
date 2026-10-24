# Hotspot Mirror

One Kotlin app, two modes, everything local over the hotspot Wi-Fi (no internet, no cloud).

- **Sender** – on the hotspot phone (has the SIM). Forwards SMS (with OTP extraction), call alerts (number + contact name) and all notifications.
- **Viewer** – on your personal phone, which is always joined to that hotspot. Shows what arrives as local notifications (OTPs get a "Copy code" action), keeps a feed, lists connected devices.

## Architecture (Phase 1)

```
 Hotspot phone (Sender)                          Personal phone (Viewer)
 ┌────────────────────────────┐                  ┌──────────────────────────┐
 │ SmsReceiver ─┐             │                  │ ViewerService            │
 │ CallReceiver ┼─▶ SenderService ══ TCP+AES-GCM ══▶ NSD browse + connect   │
 │ NotificationMirrorService ─┘│   (port 47821)   │  ├─ local notifications  │
 │ NSD register _hotspotmirror │◀─ mDNS ─────────▶│  ├─ Copy-code action     │
 └────────────────────────────┘                  │  └─ ViewerState → UI     │
                                                  └──────────────────────────┘
```

| Concern | Choice |
|---|---|
| Discovery | `NsdManager` / mDNS, service type `_hotspotmirror._tcp.`; Viewer holds a `MulticastLock`. |
| Pairing | Sender shows a 12-char code (60 bits). Typed once into the Viewer. |
| Key derivation | PBKDF2-HMAC-SHA256(code) → pairing key; per-connection nonces from both sides → session key (HMAC-SHA256). |
| Transport | TCP, length-prefixed frames, AES-256-GCM, per-direction counter IVs. A wrong code fails the first frame's auth tag and the socket is dropped. No plaintext ever carries message data. |
| Wire format | One JSON object per frame (`core/Message.kt`): hello, sms, call, notif, devices, ping. |
| Background | Foreground service (`connectedDevice`) + partial wake lock + `START_STICKY` + `BootReceiver` + battery-optimisation exemption prompt. Viewer auto-reconnects every 2 s. |
| Sender sources | `RECEIVE_SMS`, `PHONE_STATE`/`READ_CALL_LOG` (number), `READ_CONTACTS` (name), Notification Listener access (user enables in Settings). |

Threat model: anyone on the hotspot can reach the port, but without the code they cannot read or inject messages. Hotspot Wi-Fi password is the first gate, the pairing code the second.

### Known limits
- Android 10+ restricts apps on boot-start and background activity launches; the services are foreground so they are fine, but OEMs (Xiaomi, Oppo, Vivo, Samsung "sleeping apps") add their own kill lists. Users need to also set "Autostart"/"Unrestricted battery" in OEM settings.
- Incoming call numbers need `READ_CALL_LOG` on Android 9+. Google Play restricts that permission and SMS permissions; this is a sideload app, which is fine for personal use.
- Notification content hidden by "sensitive notifications" setting on Android 15 may arrive redacted.
- Not yet built or run: this environment has no Android SDK. First step on your side is `./gradlew assembleDebug` in Android Studio.

## Phase 2 – call audio over Bluetooth (needs a decision)

Call audio cannot be captured by an app on the Wi-Fi path (Android blocks `VOICE_CALL` capture for normal apps), so Bluetooth HFP is the only legit route. The catch is the **role** each phone must play:

- The hotspot phone has to be the HFP **Audio Gateway** (AG). Stock Android already does this for any paired headset, no app needed.
- The personal phone has to be the HFP **Hands-Free** (HF) unit. Stock Android phones do **not** expose the HF role to apps: `BluetoothHeadsetClient` is a system/hidden API, usually compiled out on phones (it exists on Android Automotive and some car builds).

So "the personal phone acts as the headset" most likely won't work on stock firmware with a normal app. Realistic options, best first:

1. **Check your two phones.** If the personal phone is a Pixel/AOSP-like build or rooted with HFP client enabled, a system-privileged app can use `BluetoothHeadsetClient`. A 15-minute test tells us.
2. **Dedicated HF device** (cheap Bluetooth headset or car kit) paired to the hotspot phone; the app just shows call alerts. Zero code.
3. **Speakerphone bridge:** Sender forces speaker and streams mic audio (it hears the other party acoustically) over the existing encrypted socket; Viewer sends mic audio back to the Sender's speaker. Works without BT or root, but quality is poor and echo cancellation is hard.

Recommendation: do option 1's quick check on your devices, then decide between 2 and 3. Phase 1 is independent.
