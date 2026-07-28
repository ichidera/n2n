#!/usr/bin/env python3
"""
n2n-lite hub relay - authenticated, token-bound, zero-config
----------------------------------------------------------------
Runs on your PC. Three ports, now with real (if lightweight) security:

  UDP 7776 (discovery)  - answers "where's the hub?" broadcasts/unicasts
  UDP 7777 (relay)      - forwards packets between edges, floods broadcast
  UDP 7778 (allocator)  - hands out a virtual IP + a private session token

SECURITY MODEL
--------------
This was previously a fully open relay: anyone on the LAN could claim any
virtual IP just by forging the source field in the fake IP header they
sent. That's fixed here with two mechanisms:

1. NETWORK_KEY -- a shared secret string. Every request (discovery, alloc,
   list) must include it, or it's silently ignored. Change this from the
   default before using this on anything but a fully trusted LAN.

2. Per-device session tokens -- when a device is allocated a virtual IP,
   it's also given a random private token. Every packet sent to the relay
   port must be prefixed with that token. The hub only accepts a packet
   claiming to be from a given virtual IP if the attached token matches
   the one *that specific device* was issued -- so a rogue device on the
   LAN can no longer hijack another device's IP by forging headers, since
   it doesn't know the private token bound to that IP.

   Note: this is deliberately NOT MAC-address binding. This relay never
   sees MAC addresses at all -- Android's VpnService TUN interface and
   plain UDP sockets both operate above Layer 2. A per-device secret
   token is the correct equivalent for this architecture (and is not
   trivially spoofable the way a claimed MAC address would be anyway).

Still no full authentication/encryption of payload contents -- this
remains designed for a trusted home LAN, just no longer a fully open one.

Requires only the Python standard library. Run with:
    python hub_relay.py
"""

import asyncio
import json
import os
import re
import socket
import time
import uuid

# ---------------------------------------------------------------------------
# CHANGE THIS before using on anything but a fully isolated test network.
# Every device's build must use the exact same value (see Config.NETWORK_KEY
# in TunLanService.kt).
NETWORK_KEY = "changeme-shared-secret"
# ---------------------------------------------------------------------------

PEERS: dict[str, tuple[tuple[str, int], float]] = {}   # virtual_ip -> ((real_ip, real_port), last_seen)
IP_TOKENS: dict[str, str] = {}                          # virtual_ip -> private session token
NAMES: dict[str, str] = {}                              # virtual_ip -> sanitized friendly name
LEASES: dict[str, dict] = {}                            # client_id -> {"ip":..., "token":...}

LEASES_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "leases.json")

DISCOVERY_PORT = 7776
RELAY_PORT = 7777
ALLOC_PORT = 7778
TOKEN_LEN = 36  # length of a str(uuid.uuid4())

SUBNET_PREFIX = "10.10.10."
PEER_TIMEOUT_SECONDS = 45
_next_octet = 2

_SANITIZE_NAME_RE = re.compile(r"[^A-Za-z0-9 _.\-]")


def sanitize_name(raw: str) -> str:
    """Strips anything that isn't a plain safe character and truncates,
    regardless of what the client claims to have already sanitized --
    the hub must not trust client-side sanitization."""
    cleaned = _SANITIZE_NAME_RE.sub("", raw).strip()
    return cleaned[:32]


def load_leases() -> None:
    global _next_octet
    if os.path.exists(LEASES_FILE):
        try:
            with open(LEASES_FILE, "r") as f:
                data = json.load(f)
            LEASES.update(data)
            for entry in LEASES.values():
                IP_TOKENS[entry["ip"]] = entry["token"]
            if LEASES:
                used_octets = [int(e["ip"].split(".")[-1]) for e in LEASES.values()]
                _next_octet = max(used_octets) + 1
            print(f"[hub] loaded {len(LEASES)} saved lease(s) from {LEASES_FILE}")
        except Exception as e:
            print(f"[hub] could not load leases file ({e}), starting fresh")


def save_leases() -> None:
    try:
        with open(LEASES_FILE, "w") as f:
            json.dump(LEASES, f, indent=2)
    except Exception as e:
        print(f"[hub] could not save leases file: {e}")


def allocate(client_id: str) -> tuple[str, str]:
    """Returns (ip, token) for a client_id, creating a new lease + token
    only if one doesn't already exist."""
    global _next_octet
    existing = LEASES.get(client_id)
    if existing:
        return existing["ip"], existing["token"]

    ip = f"{SUBNET_PREFIX}{_next_octet}"
    _next_octet += 1
    token = str(uuid.uuid4())
    LEASES[client_id] = {"ip": ip, "token": token}
    IP_TOKENS[ip] = token
    save_leases()
    print(f"[alloc] {client_id} -> {ip}")
    return ip, token


class Discovery(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] discovery listening on UDP {DISCOVERY_PORT}")

    def datagram_received(self, data: bytes, addr):
        try:
            req = json.loads(data.decode("utf-8"))
        except Exception:
            return
        if req.get("key") != NETWORK_KEY:
            return  # wrong/missing shared secret, ignore silently
        if req.get("magic") != "LANBRIDGE_DISCOVER":
            return
        reply = json.dumps({"magic": "LANBRIDGE_HUB"}).encode("utf-8")
        self.transport.sendto(reply, addr)


class Relay(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] data relay listening on UDP {RELAY_PORT}")

    def datagram_received(self, data: bytes, addr):
        if len(data) < TOKEN_LEN + 20:
            return  # too short to contain a token + a minimal IPv4 header

        token = data[:TOKEN_LEN].decode("ascii", errors="ignore")
        payload = data[TOKEN_LEN:]

        src_ip = socket.inet_ntoa(payload[12:16])
        dst_ip = socket.inet_ntoa(payload[16:20])

        expected_token = IP_TOKENS.get(src_ip)
        if expected_token is None or token != expected_token:
            # Either this IP was never issued, or the sender doesn't know
            # the private token bound to it -- reject. This is what stops
            # a rogue device from claiming someone else's virtual IP.
            return

        existing = PEERS.get(src_ip)
        if existing is None or existing[0] != addr:
            print(f"[hub] peer {src_ip} -> {addr}")
        PEERS[src_ip] = (addr, time.time())

        last_octet = int(dst_ip.split(".")[-1])
        first_octet = int(dst_ip.split(".")[0])
        is_broadcast = last_octet == 255
        is_multicast = 224 <= first_octet <= 239

        if is_broadcast or is_multicast:
            for vip, (paddr, _last_seen) in PEERS.items():
                if paddr != addr:
                    self.transport.sendto(payload, paddr)
        else:
            entry = PEERS.get(dst_ip)
            if entry:
                self.transport.sendto(payload, entry[0])


class Allocator(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] IP allocator listening on UDP {ALLOC_PORT}")

    def datagram_received(self, data: bytes, addr):
        try:
            req = json.loads(data.decode("utf-8"))
        except Exception:
            return

        if req.get("key") != NETWORK_KEY:
            return  # wrong/missing shared secret, ignore silently

        action = req.get("action", "alloc")

        if action == "list":
            now = time.time()
            peers = [
                {"ip": ip, "name": NAMES.get(ip, ip)}
                for ip, (_paddr, last_seen) in PEERS.items()
                if now - last_seen <= PEER_TIMEOUT_SECONDS
            ]
            reply = json.dumps({"peers": peers}).encode("utf-8")
            self.transport.sendto(reply, addr)
            return

        client_id = str(req.get("client_id", ""))
        if not client_id:
            return

        ip, token = allocate(client_id)

        raw_name = str(req.get("name", ""))
        if raw_name:
            NAMES[ip] = sanitize_name(raw_name)  # always sanitized here, regardless of client

        reply = json.dumps({"ip": ip, "token": token, "hub_ip": SUBNET_PREFIX + "1"}).encode("utf-8")
        self.transport.sendto(reply, addr)


async def prune_stale_peers():
    while True:
        await asyncio.sleep(20)
        now = time.time()
        stale = [ip for ip, (_addr, last_seen) in PEERS.items() if now - last_seen > PEER_TIMEOUT_SECONDS]
        for ip in stale:
            print(f"[hub] peer {ip} timed out, removing from active list")
            del PEERS[ip]


async def main():
    load_leases()
    if NETWORK_KEY == "changeme-shared-secret":
        print("[hub] WARNING: still using the default NETWORK_KEY. Change it in this "
              "file and in Config.NETWORK_KEY on the Android side before relying on this.")
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(Discovery, local_addr=("0.0.0.0", DISCOVERY_PORT))
    await loop.create_datagram_endpoint(Relay, local_addr=("0.0.0.0", RELAY_PORT))
    await loop.create_datagram_endpoint(Allocator, local_addr=("0.0.0.0", ALLOC_PORT))
    asyncio.create_task(prune_stale_peers())
    print("Hub running (authenticated, token-bound). Press Ctrl+C to stop.")
    try:
        await asyncio.Event().wait()
    except asyncio.CancelledError:
        pass


if __name__ == "__main__":
    asyncio.run(main())