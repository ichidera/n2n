# LAN Bridge (n2n-lite)

A minimal, self-hosted broadcast-capable virtual LAN, purpose-built to make
Mini Militia's (or any) local WiFi game discovery work between BlueStacks,
MEmu, and a real phone. No accounts, no cloud, no root required.

## Why this exists
Real routing (WireGuard, IP forwarding, etc.) only delivers packets addressed
to a specific IP. Game discovery relies on **broadcast** packets sent to
`10.10.10.255`, which normal routers/tunnels never replicate to other peers.
This project adds that one missing piece: a hub that floods broadcast/
multicast packets to every connected device.

## Parts
- `hub_relay.py` — runs on your PC. Pure Python standard library, no installs needed.
- `android-app/` — an Android Studio project. Builds one small app that:
  creates a TUN interface via Android's built-in VpnService (no root needed),
  and forwards every packet to/from the hub over UDP.

## Setup

### 1. Run the hub on your PC
```
python hub_relay.py
```
Leave this running. It listens on UDP port 7777 and needs no configuration —
it learns each device's real address automatically.

Make sure Windows Firewall allows inbound UDP 7777 for Python, or add a rule:
```powershell
netsh advfirewall firewall add rule name="LAN Bridge Hub" protocol=UDP dir=in localport=7777 action=allow
```

### 2. Open the Android project
Open the `android-app/` folder in Android Studio (free download from
developer.android.com). Let Gradle sync — first sync will download the
Android SDK/Gradle dependencies, which needs internet access on your machine
(this part can't be done from a sandboxed environment, hence doing it in
Android Studio directly).

### 3. Set each device's config
Before building for each device, edit the three constants at the top of
`TunLanService.kt`:
```kotlin
const val VIRTUAL_IP = "10.10.10.2"   // give each device a unique one: .2, .3, .4, .5, .6
const val HUB_IP = "192.168.0.11"     // your PC's real LAN IP (from ipconfig)
const val HUB_PORT = 7777
```

### 4. Build and install
- For BlueStacks/MEmu: `adb install app-debug.apk` (Android Studio can build
  this for you via Build > Build Bundle(s)/APK(s) > Build APK(s), or run
  `./gradlew assembleDebug` from the project folder).
- For your phone: same APK, just with `VIRTUAL_IP = "10.10.10.6"` before
  that particular build (or just change it and rebuild for each target,
  since each device needs its own IP).

### 5. Run it
Open the app on each device, tap "Start Bridge", accept the one-time Android
VPN permission prompt. Do this on all devices before hosting the game.

### 6. Test
From your PC:
```
adb -s <serial> shell ping -c 4 10.10.10.3
```
should work between any pair, same as before. The real test is opening
Mini Militia on all devices and hosting a WiFi game on one — the others
should now see it in the local game list, since broadcast packets are being
flooded by the hub to every connected device.

## Troubleshooting
- If a device can ping others but the game still doesn't see it: double
  check `VIRTUAL_IP` is unique per device — two devices with the same IP
  will silently overwrite each other's entry in the hub's peer table.
- If nothing connects at all: confirm the PC's Windows Firewall isn't
  blocking inbound UDP 7777 (see command above), and confirm `HUB_IP` in
  each device matches your PC's actual LAN IP from `ipconfig`.
