#!/usr/bin/env python3
"""
n2n-lite hub relay - zero-config, self-registering, persistent, with peer list
--------------------------------------------------------------------------------
Runs on your PC. Four jobs, three ports:

  UDP 7776 (discovery)  - answers "where's the hub?" broadcasts
  UDP 7777 (relay)      - forwards packets between edges, floods broadcast
  UDP 7778 (allocator)  - hands out a virtual IP, tracks names, lists peers

Devices never need to know your PC's IP or type in any config. On top of
the original zero-config relay, this version adds:

  - Persistent IP leases: assignments survive a hub restart (saved to
    leases.json next to this script).
  - Friendly device names: each device can send along a name (e.g. its
    model) that shows up in the peer list instead of just an IP.
  - A live peer list: any device can ask "who else is online right now?"
    and get back a list of currently-connected devices (based on which
    ones have sent traffic/keepalives recently, not just ever-assigned).

Requires only the Python standard library. Run with:
    python hub_relay.py
"""

import asyncio
import json
import os
import socket
import time

PEERS: dict[str, tuple[tuple[str, int], float]] = {}   # virtual_ip -> ((real_ip, real_port), last_seen)
NAMES: dict[str, str] = {}                              # virtual_ip -> friendly device name
LEASES: dict[str, str] = {}                             # client_id -> assigned virtual_ip

LEASES_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "leases.json")

DISCOVERY_PORT = 7776
RELAY_PORT = 7777
ALLOC_PORT = 7778
DISCOVERY_MAGIC = b"LANBRIDGE_DISCOVER"
DISCOVERY_REPLY = b"LANBRIDGE_HUB"

SUBNET_PREFIX = "10.10.10."
PEER_TIMEOUT_SECONDS = 45   # a peer missing 3 keepalives (15s apart) is considered offline
_next_octet = 2             # .1 is reserved for the hub itself


def load_leases() -> None:
    global _next_octet
    if os.path.exists(LEASES_FILE):
        try:
            with open(LEASES_FILE, "r") as f:
                LEASES.update(json.load(f))
            if LEASES:
                used_octets = [int(ip.split(".")[-1]) for ip in LEASES.values()]
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


def allocate_ip(client_id: str) -> str:
    global _next_octet
    if client_id in LEASES:
        return LEASES[client_id]
    ip = f"{SUBNET_PREFIX}{_next_octet}"
    LEASES[client_id] = ip
    _next_octet += 1
    save_leases()
    print(f"[alloc] {client_id} -> {ip}")
    return ip


class Discovery(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] discovery listening on UDP {DISCOVERY_PORT}")

    def datagram_received(self, data: bytes, addr):
        if data.strip() == DISCOVERY_MAGIC:
            self.transport.sendto(DISCOVERY_REPLY, addr)


class Relay(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] data relay listening on UDP {RELAY_PORT}")

    def datagram_received(self, data: bytes, addr):
        if len(data) < 20:
            return  # not a valid IPv4 packet

        src_ip = socket.inet_ntoa(data[12:16])
        dst_ip = socket.inet_ntoa(data[16:20])

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
                    self.transport.sendto(data, paddr)
        else:
            entry = PEERS.get(dst_ip)
            if entry:
                self.transport.sendto(data, entry[0])


class Allocator(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] IP allocator listening on UDP {ALLOC_PORT}")

    def datagram_received(self, data: bytes, addr):
        try:
            req = json.loads(data.decode("utf-8"))
        except Exception:
            return  # ignore malformed requests

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

        ip = allocate_ip(client_id)
        name = str(req.get("name", "")).strip()
        if name:
            NAMES[ip] = name

        reply = json.dumps({"ip": ip, "hub_ip": SUBNET_PREFIX + "1"}).encode("utf-8")
        self.transport.sendto(reply, addr)


async def prune_stale_peers():
    """Periodically drops peers that have gone quiet, so the peer list
    stays accurate rather than showing devices that disconnected."""
    while True:
        await asyncio.sleep(20)
        now = time.time()
        stale = [ip for ip, (_addr, last_seen) in PEERS.items() if now - last_seen > PEER_TIMEOUT_SECONDS]
        for ip in stale:
            print(f"[hub] peer {ip} timed out, removing from active list")
            del PEERS[ip]


async def main():
    load_leases()
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(Discovery, local_addr=("0.0.0.0", DISCOVERY_PORT))
    await loop.create_datagram_endpoint(Relay, local_addr=("0.0.0.0", RELAY_PORT))
    await loop.create_datagram_endpoint(Allocator, local_addr=("0.0.0.0", ALLOC_PORT))
    asyncio.create_task(prune_stale_peers())
    print("Hub running (zero-config, persistent leases, peer list). Press Ctrl+C to stop.")
    try:
        await asyncio.Event().wait()
    except asyncio.CancelledError:
        pass


if __name__ == "__main__":
    asyncio.run(main())