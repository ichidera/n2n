# LAN Bridge (n2n-lite)

A minimal, self-hosted broadcast-capable virtual LAN, purpose-built to make
Mini Militia's (or any) local WiFi game discovery work between BlueStacks,
MEmu, and a real phone. No accounts, no cloud, no root required, and no
per-device configuration -- install the same APK everywhere.

## Why this exists

Real routing (WireGuard, IP forwarding, etc.) only delivers packets addressed
to a specific IP. Game discovery relies on **broadcast** packets sent to
`10.10.10.255`, which normal routers/tunnels never replicate to other peers.
This project adds that one missing piece: a hub that floods broadcast/
multicast packets to every connected device.

On top of that, every device automatically requests its own virtual IP from
the hub the first time it connects -- there's no `VIRTUAL_IP` constant to
edit per build. The hub remembers each device by a random ID it generates
once and stores locally, so the same device always gets the same IP.

## Parts

- **`hub_relay.py`** -- runs on your PC. Pure Python standard library, no
  installs needed. Does two jobs on two ports:
  - **UDP 7777** -- the data relay. Forwards unicast packets to the right
    peer and floods broadcast/multicast packets to everyone else.
  - **UDP 7778** -- the IP allocator. Hands out a virtual IP to any device
    that asks, keyed by a random ID that device generates once.
- **`android-app/`** -- an Android Studio project. Builds one small app
  that creates a TUN interface via Android's built-in `VpnService` (no root
  needed), requests its IP from the hub automatically, and forwards every
  packet to/from the hub over UDP.

## Setup

### 1. Run the hub on your PC

```
python hub_relay.py
```

Leave this running. On start you should see:

```
[hub] data relay listening on UDP 7777
[hub] IP allocator listening on UDP 7778
```

As each device connects for the first time, you'll see a line like:

```
[alloc] 3f2a9c1e-... -> 10.10.10.2
```

Make sure Windows Firewall allows both ports inbound for Python, or add
rules for each:

```powershell
netsh advfirewall firewall add rule name="LAN Bridge Relay" protocol=UDP dir=in localport=7777 action=allow
netsh advfirewall firewall add rule name="LAN Bridge Alloc" protocol=UDP dir=in localport=7778 action=allow
```

### 2. Open the Android project

Open the `android-app/` folder in Android Studio (free download from
developer.android.com). Let Gradle sync -- first sync will download the
Android SDK/Gradle dependencies, which needs your own machine's internet
access (this part can't be done from a sandboxed environment, hence doing
it in Android Studio directly).

### 3. Set the hub address (once, for the whole project)

Open `TunLanService.kt` and check the one constant that matters:

```kotlin
object Config {
    const val HUB_IP = "192.168.0.11"     // your PC's real LAN IP
    const val HUB_PORT = 7777             // data relay port
    const val ALLOC_PORT = 7778           // IP allocation port
}
```

Set `HUB_IP` to your PC's actual LAN IP (from `ipconfig`). That's it --
no per-device IP to assign. Every device that installs this same build
will request its own IP automatically.

### 4. Build and install

- Build once: **Build > Build Bundle(s) / APK(s) > Build APK(s)** (or run
  `./gradlew assembleDebug` from the project folder).
- Install the **same** `app-debug.apk` on every device: BlueStacks x2,
  MEmu, and your phone. `adb install app-debug.apk` for the emulators,
  sideload for the phone.

### 5. Run it

Open the app on each device and tap **Start Bridge**. Accept the one-time
Android VPN permission prompt. Watch the on-screen status text go:

```
Requesting virtual IP from hub...
Assigned IP: 10.10.10.x
Connected as 10.10.10.x
```

Do this on all devices before hosting the game. Each one will land on a
different `10.10.10.x` address automatically.

### 6. Test

From your PC, find each device's assigned IP either from the hub's console
output or the app's on-screen status, then:

```
adb -s <serial> shell ping -c 4 10.10.10.3
```

should work between any pair -- pinging the hub (`10.10.10.1`) confirms the
tunnel is up; pinging another device's assigned IP confirms peer-to-peer
routing through the relay works. The real test is opening Mini Militia on
all devices and hosting a WiFi game on one -- the others should now see it
in the local game list, since broadcast packets are being flooded by the
hub to every connected device.

## Troubleshooting

- **App shows "Could not reach hub"** -- confirm `hub_relay.py` is running
  and `HUB_IP` in `TunLanService.kt` matches your PC's actual LAN IP from
  `ipconfig`. Also confirm the port 7778 firewall rule above is in place.
- **Two devices seem to collide** -- shouldn't happen since IDs are random
  UUIDs generated once per install, but if you cloned an app's data/APK
  directly (rather than a fresh install) the ID could carry over. A fresh
  install (uninstall + reinstall) generates a new one.
- **A device keeps changing IP between runs** -- the hub's lease table
  (`LEASES` in `hub_relay.py`) lives in memory only; if you restart the
  hub script, leases reset and devices get reassigned from `.2` up again
  in whatever order they reconnect. This doesn't break anything for LAN
  gaming purposes, but if you want stable IPs across hub restarts, persist
  `LEASES` to a JSON file on disk (not included by default, kept minimal).
- **Game still doesn't see other devices even though pings work** --
  double check all devices actually tapped "Start Bridge" and show
  "Connected" before opening the game.