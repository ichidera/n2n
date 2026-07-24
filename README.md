# LAN Bridge (n2n-lite)

A minimal, self-hosted broadcast-capable virtual LAN, purpose-built to make
Mini Militia's (or any) local WiFi game discovery work between BlueStacks,
MEmu, and a real phone. No accounts, no cloud, no root, and **no
configuration on any device** -- install the same APK everywhere and it
finds the hub and assigns itself an IP automatically.

## Why this exists

Real routing (WireGuard, IP forwarding, etc.) only delivers packets addressed
to a specific IP. Game discovery relies on **broadcast** packets sent to
`255.255.255.255`, which normal routers/tunnels never replicate to other
peers -- and won't even enter a typical VPN tunnel unless the tunnel is
explicitly told to capture that address. This project adds both missing
pieces: a hub that floods broadcast/multicast packets to every connected
device, and a VPN route table that actually captures that traffic in the
first place.

On top of that:

- Every device **auto-discovers** the hub by broadcasting on its own local
  subnet, and **auto-requests** its own virtual IP -- nothing to type in.
- Devices **self-register** with the hub the instant they connect (not just
  when they happen to send real traffic), so the very first ping or game
  packet to a freshly-connected device works immediately.
- IP **leases persist** across hub restarts, saved to `leases.json`.
- Each device can send a **friendly name** (its model), so the built-in
  peer list is readable instead of just showing raw IPs.
- The app has a **live peer list** -- tap Refresh to see who else is
  currently connected, by name.

### How zero-config discovery works

Your PC sits on every device's network simultaneously: your real WiFi/LAN,
plus the gateway of each emulator's private virtual switch (you can see
this yourself in `ipconfig` -- addresses like `172.30.96.1` for BlueStacks
and `172.17.48.1` for MEmu, alongside your real `192.168.0.11`). When a
device broadcasts "where's the hub?" on its own subnet, that broadcast
naturally reaches your PC's matching interface for that subnet, and your
PC replies from that same interface. The reply's source address *is*
the correct hub IP for that specific device -- no configuration required.

Some emulators' virtual network adapters don't forward broadcast traffic at
all (this showed up with MEmu in testing, even though basic unicast ping
worked fine) -- for those, the app has a **manual hub IP override** field
as a fallback; leave it blank everywhere else.

## Parts

- **`hub_relay.py`** -- runs on your PC. Pure Python standard library, no
  installs needed. Does three jobs on three ports:
  - **UDP 7776** -- discovery. Answers "where's the hub?" broadcasts.
  - **UDP 7777** -- the data relay. Forwards unicast packets to the right
    peer and floods broadcast/multicast packets to everyone else.
  - **UDP 7778** -- the IP allocator. Hands out a virtual IP (remembered
    permanently per device via `leases.json`), stores each device's
    friendly name, and answers "who's online right now?" queries.
- **`android-app/`** -- an Android Studio project. Builds one small app
  that creates a TUN interface via Android's built-in `VpnService` (no
  root needed), finds the hub by broadcast, requests its IP automatically,
  registers itself immediately, and forwards every packet (including
  broadcast/multicast) to/from the hub over UDP. Also shows a live list
  of connected devices.

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

(If a `leases.json` already exists next to the script from a previous run,
you'll also see `[hub] loaded N saved lease(s)`.)

As each device connects, you'll see lines like:

```
[discover] request from ('172.30.96.5', 51422) -> replying
[alloc] 3f2a9c1e-... -> 10.10.10.2
[hub] peer 10.10.10.2 -> ('172.30.96.5', 51422)
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

**If a particular emulator's broadcast doesn't reach the hub** (auto-discovery
times out even though the emulator has working internet), type its gateway
IP into the "Manual hub IP" field before tapping Start Bridge -- you can find
that address from `ipconfig` on your PC (it's whatever `vEthernet` adapter
corresponds to that emulator) or by pinging a few candidates via `adb shell
ping` from that instance.

### 5. Check who's connected

Tap **Refresh Peer List** in the app to see every other currently-connected
device by name (e.g. `Redmi Note 12 (10.10.10.4)`). This is a good sanity
check before opening the game -- if a device you expect isn't listed, its
bridge isn't actually up yet.

### 6. Test connectivity directly (optional but useful)

From your PC:

```
adb -s <serial> shell ping -c 4 10.10.10.3
```

should work between any pair with 0% packet loss, confirming the tunnel
and relay are both healthy before you even open the game.

### 7. Play

Open Mini Militia (or any local WiFi game) on all devices and host a game
on one. The others should now see it in the local game list -- broadcast
discovery packets are captured into the tunnel and flooded by the hub to
every connected device automatically.

## Troubleshooting

- **App shows "No hub found"** -- confirm `hub_relay.py` is running and
  the port 7776 firewall rule above is in place. If it still fails on a
  specific emulator while others work fine, that emulator's virtual
  network likely doesn't forward broadcast traffic (seen with MEmu in
  testing) -- use the manual hub IP field for that one device instead.
- **Pings between devices work but the game still doesn't see anyone** --
  confirm you're running the version with the broadcast/multicast routes
  (`.addRoute("255.255.255.255", 32)` and `.addRoute("224.0.0.0", 4)` in
  `TunLanService.kt`). Without those, the tunnel only carries traffic
  explicitly addressed within `10.10.10.0/24`, and game discovery broadcasts
  never enter it at all.
- **A device that should be online doesn't show up in the peer list** --
  the list only includes devices that have sent traffic or a keepalive in
  the last 45 seconds. Confirm that device's status text says "Connected",
  not still "Searching..." or an error.
- **Two devices seem to collide** -- shouldn't happen since IDs are random
  UUIDs generated once per install, but if you cloned an app's data/APK
  directly (rather than a fresh install) the ID could carry over. A fresh
  install (uninstall + reinstall) generates a new one.
- **Leases don't seem to persist** -- confirm `leases.json` is being written
  next to `hub_relay.py` (check its timestamp after a device connects) and
  that the script has write permission in that folder.