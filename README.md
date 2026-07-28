# LAN Bridge (n2n-lite)

A minimal, self-hosted broadcast-capable virtual LAN, purpose-built to make
Mini Militia's (or any) local WiFi game discovery work between BlueStacks,
MEmu, and a real phone. No accounts, no cloud, no root, and **no
configuration on any device** -- install the same APK everywhere and it
finds the hub and assigns itself an IP automatically.

## What's new in this version

This revision responds to a security/robustness review of the previous
build. Summary of what changed and why:

| Concern | Fix |
|---|---|
| Any device could forge another's virtual IP (no source validation) | Per-device session tokens (see below) |
| Peer names not sanitized server-side | Hub now always re-sanitizes regardless of client |
| No authentication anywhere | Shared `NETWORK_KEY` required on every request |
| Service never called `startForeground()` -- OS could silently kill the tunnel | Fixed, with proper Android 14 `specialUse` type |
| No `onRevoke()` handling | Added, shares cleanup logic with `onDestroy()` |
| Hub IP not persisted -- peer list broke across Activity recreation | Persisted to `SharedPreferences` |
| Peer-list parsing was regex-based | Switched to real `org.json` parsing |
| No emulator detection | Added `EmulatorDetector`, used to inform networking |

### A note on "MAC binding"

True MAC-address binding isn't possible in this architecture: Android's
`VpnService` TUN interface and plain UDP sockets both operate above Layer 2,
so this relay never sees a MAC address at any point, on any device. It
also wouldn't help much even if it were available -- a claimed MAC address
is just as forgeable as a claimed source IP was.

The fix that actually addresses the underlying concern is **per-device
session tokens**: when the hub allocates a virtual IP to a device, it also
hands back a random private token. Every packet sent to the relay port
must be prefixed with that token, and the hub only relays/registers a
packet if the attached token matches the one issued for that specific
virtual IP. A rogue device on the LAN can no longer claim `10.10.10.3`
just by writing that address into a packet header -- it would also need
to know the private token bound to it, which was only ever sent privately
to the device that legitimately holds that IP.

### Shared network key

Every request now requires a shared secret string (`NETWORK_KEY`) to match
between the hub and every device's build. **Change the default value**
(`changeme-shared-secret`) in both `hub_relay.py` and
`Config.NETWORK_KEY` in `TunLanService.kt` to the same new string before
relying on this for anything beyond initial testing -- they must match
exactly, character for character, since this is a static shared secret
baked into your own private build (the same model tools like Hamachi use
for a "network name/password"), not something entered per session.

### Emulator detection

`EmulatorDetector.kt` uses the same general approach production apps use
for environment checks: no single signal is proof on its own (all are
spoofable in principle), so several are combined -- known file paths
(BlueStacks shares a Windows folder into the guest at a fixed location),
`Build.MANUFACTURER`/`BRAND`/`PRODUCT`/`HARDWARE` fields specific vendors
set, and generic QEMU/AVD signals. This is surfaced in the app UI ("Platform:
BlueStacks", etc.) and feeds directly into networking: see below.

### Knowing "the emulator's true IP" without running ipconfig

The app obviously can't run `ipconfig` on your Windows host directly --
that's a different machine. But it doesn't need to: it can read its own
IP and subnet from inside the guest (exactly the same information
`ipconfig` would show for that specific virtual adapter on the host side)
and derive the gateway from it. Empirically, across every BlueStacks and
MEmu instance tested, the host's address on that virtual switch was always
the `.1` of whatever subnet the guest landed on. So the app now:

1. Tries broadcast discovery first (works on BlueStacks and real WiFi).
2. If that fails, computes its own subnet's `.1` address and sends a
   **direct unicast probe** to it, requiring a valid signed reply before
   trusting it -- this isn't a blind guess, a wrong guess just fails
   cleanly. This is what fixes MEmu automatically: its unicast networking
   works fine even though its broadcast doesn't, so this probe succeeds
   without ever needing the manual IP field.
3. Only falls back to the manual override field if both of the above fail.

## Why this exists

Real routing (WireGuard, IP forwarding, etc.) only delivers packets addressed
to a specific IP. Game discovery relies on **broadcast** packets sent to
`255.255.255.255`, which normal routers/tunnels never replicate to other
peers -- and won't even enter a typical VPN tunnel unless the tunnel is
explicitly told to capture that address. This project adds both missing
pieces: a hub that floods broadcast/multicast packets to every connected
device, and a VPN route table that actually captures that traffic in the
first place.

## Parts

- **`hub_relay.py`** -- runs on your PC. Pure Python standard library, no
  installs needed. Three ports:
  - **UDP 7776** -- discovery (now requires the shared key).
  - **UDP 7777** -- the data relay. Requires a valid per-device session
    token on every packet; forwards unicast to the right peer and floods
    broadcast/multicast to everyone else.
  - **UDP 7778** -- the IP allocator. Hands out a virtual IP + private
    token (persisted across restarts via `leases.json`), stores each
    device's sanitized friendly name, answers "who's online?" queries.
- **`android-app/`** -- an Android Studio project. Builds one small app
  that creates a TUN interface via Android's built-in `VpnService` (no
  root needed), detects which emulator (if any) it's running on, finds
  the hub automatically, requests its IP + token, registers itself
  immediately, and forwards every packet (including broadcast/multicast)
  to/from the hub over UDP -- now as a proper foreground service so it
  survives being backgrounded. Also shows a live peer list.

## Setup

### 1. Change the shared secret

Before building, edit `NETWORK_KEY` in `hub_relay.py` and
`Config.NETWORK_KEY` in `TunLanService.kt` to the same new value. They
must match exactly on both sides.

### 2. Run the hub on your PC

```
python hub_relay.py
```

Leave this running. On start you should see:

```
[hub] discovery listening on UDP 7776
[hub] data relay listening on UDP 7777
[hub] IP allocator listening on UDP 7778
```

(If a `leases.json` already exists from a previous run, you'll also see
`[hub] loaded N saved lease(s)`. If you forgot to change the default key,
the hub prints a loud warning at startup as a reminder.)

Make sure Windows Firewall allows all three ports inbound for Python:

```powershell
netsh advfirewall firewall add rule name="LAN Bridge Discover" protocol=UDP dir=in localport=7776 action=allow
netsh advfirewall firewall add rule name="LAN Bridge Relay" protocol=UDP dir=in localport=7777 action=allow
netsh advfirewall firewall add rule name="LAN Bridge Alloc" protocol=UDP dir=in localport=7778 action=allow
```

### 3. Open the Android project

Open the `android-app/` folder in Android Studio (free download from
developer.android.com). Let Gradle sync -- first sync needs your own
machine's internet access, which can't be done from a sandboxed
environment, hence doing it in Android Studio directly.

### 4. Build and install

- **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
- Install the **same** `app-debug.apk` on every device: BlueStacks x2,
  MEmu, and your phone.

### 5. Run it

Open the app on each device and tap **Start Bridge**. On Android 13+
you'll also get a one-time notification permission prompt (needed for the
persistent "bridge active" notification the foreground service now shows)
in addition to the VPN permission prompt. Watch the status text go:

```
Detected platform: BlueStacks
Searching for hub via broadcast...
Found hub at 172.30.96.1, requesting IP...
Assigned IP: 10.10.10.x
Connected as 10.10.10.x (hub 172.30.96.1, BlueStacks)
```

On a device where broadcast doesn't reach the hub, you'll instead see it
automatically fall through to the gateway-guess probe -- no manual entry
needed anymore in most cases. The manual hub IP field still exists as a
last-resort fallback.

### 6. Check who's connected

Tap **Refresh Peer List** to see every other currently-connected device by
name. This now works correctly even if you've rotated the screen or
reopened the app since connecting, since the hub address is persisted.

### 7. Play

Open Mini Militia (or any local WiFi game) on all devices and host a game
on one -- the others should see it automatically.

## Troubleshooting

- **"No hub found"** -- confirm `hub_relay.py` is running, the firewall
  rules above are in place, and `NETWORK_KEY` matches exactly on both
  sides (a mismatched key causes every request to be silently ignored,
  which looks identical to "hub isn't running" from the app's point of
  view -- check this first).
- **A device that should be online doesn't show up in the peer list** --
  the list only includes devices seen in the last 45 seconds. Confirm that
  device's status says "Connected".
- **Leases/tokens don't seem to persist** -- confirm `leases.json` is
  being written next to `hub_relay.py` and the script has write permission
  in that folder.
- **Notification for the bridge doesn't appear** -- on Android 13+, this
  requires the `POST_NOTIFICATIONS` runtime permission; the app requests
  it automatically when you tap Start Bridge, but if it was previously
  denied you'll need to grant it from the device's app settings manually.