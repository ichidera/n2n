# LAN Bridge (n2n-lite)

A minimal, self-hosted broadcast-capable virtual LAN, purpose-built to make
Mini Militia's (or any) local WiFi game discovery work between BlueStacks,
MEmu, and a real phone. No accounts, no cloud, no root, and **no
configuration on any device** -- install the same APK everywhere and it
finds the hub and assigns itself an IP automatically.

## Why this exists

Real routing (WireGuard, IP forwarding, etc.) only delivers packets addressed
to a specific IP. Game discovery relies on **broadcast** packets sent to
`10.10.10.255`, which normal routers/tunnels never replicate to other peers.
This project adds that one missing piece: a hub that floods broadcast/
multicast packets to every connected device.

On top of that, every device **auto-discovers** the hub by broadcasting on
its own local subnet, and then **auto-requests** its own virtual IP. There
is nothing to type in, on any device, ever -- not even your PC's IP address.

### How zero-config discovery works

Your PC sits on every device's network simultaneously: your real WiFi/LAN,
plus the gateway of each emulator's private virtual switch (you can see
this yourself in `ipconfig` -- addresses like `172.30.96.1` for BlueStacks
and `172.17.48.1` for MEmu, alongside your real `192.168.0.11`). When a
device broadcasts "where's the hub?" on its own subnet, that broadcast
naturally reaches your PC's matching interface for that subnet, and your
PC replies from that same interface. The reply's source address *is*
the correct hub IP for that specific device -- no configuration required,
because the OS's own routing already sorts it out per-device.

## Parts

- **`hub_relay.py`** -- runs on your PC. Pure Python standard library, no
  installs needed. Does three jobs on three ports:
  - **UDP 7776** -- discovery. Answers "where's the hub?" broadcasts.
  - **UDP 7777** -- the data relay. Forwards unicast packets to the right
    peer and floods broadcast/multicast packets to everyone else.
  - **UDP 7778** -- the IP allocator. Hands out a virtual IP to any device
    that asks, keyed by a random ID that device generates once.
- **`android-app/`** -- an Android Studio project. Builds one small app
  that creates a TUN interface via Android's built-in `VpnService` (no
  root needed), finds the hub by broadcast, requests its IP automatically,
  and forwards every packet to/from the hub over UDP.

## Setup

### 1. Run the hub on your PC

```
python hub_relay.py
```

Leave this running. On start you should see:

```
[hub] discovery listening on UDP 7776
[hub] data relay listening on UDP 7777
[hub] IP allocator listening on UDP 7778
```

As each device connects for the first time, you'll see lines like:

```
[discover] request from ('172.30.96.5', 51422) -> replying
[alloc] 3f2a9c1e-... -> 10.10.10.2
```

Make sure Windows Firewall allows all three ports inbound for Python, or
add rules for each:

```powershell
netsh advfirewall firewall add rule name="LAN Bridge Discover" protocol=UDP dir=in localport=7776 action=allow
netsh advfirewall firewall add rule name="LAN Bridge Relay" protocol=UDP dir=in localport=7777 action=allow
netsh advfirewall firewall add rule name="LAN Bridge Alloc" protocol=UDP dir=in localport=7778 action=allow
```

### 2. Open the Android project

Open the `android-app/` folder in Android Studio (free download from
developer.android.com). Let Gradle sync -- first sync will download the
Android SDK/Gradle dependencies, which needs your own machine's internet
access (this part can't be done from a sandboxed environment, hence doing
it in Android Studio directly).

### 3. Build and install -- nothing to configure

There is no config file to edit. Just build:

- **Build > Build Bundle(s) / APK(s) > Build APK(s)** (or run
  `./gradlew assembleDebug` from the project folder).
- Install the **same** `app-debug.apk` on every device: BlueStacks x2,
  MEmu, and your phone. `adb install app-debug.apk` for the emulators,
  sideload for the phone.

### 4. Run it

Open the app on each device and tap **Start Bridge**. Accept the one-time
Android VPN permission prompt. Watch the on-screen status text go:

```
Searching for hub on the network...
Found hub at 172.30.96.1, requesting IP...
Assigned IP: 10.10.10.x
Connected as 10.10.10.x (hub 172.30.96.1)
```

Do this on all devices before hosting the game. Each one will land on a
different `10.10.10.x` address, and the hub address itself will correctly
differ per device (e.g. a BlueStacks instance might show `172.30.96.1` as
its hub while your phone shows `192.168.0.11`) -- that's expected and fine,
since the hub relay listens on all of them.

### 5. Test

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

- **App shows "No hub found"** -- confirm `hub_relay.py` is running and
  the port 7776 firewall rule above is in place. If it still fails, your
  network/emulator config may be blocking UDP broadcast entirely, which is
  rare but possible on some restrictive corporate networks -- unlikely on
  a home LAN like yours.
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