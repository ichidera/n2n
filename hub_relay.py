#!/usr/bin/env python3
"""
n2n-lite hub relay - fully zero-config version
------------------------------------------------
Runs on your PC. Three jobs, three ports:

  UDP 7776 (discovery)  - answers "where's the hub?" broadcasts
  UDP 7777 (relay)      - forwards packets between edges, floods broadcast
  UDP 7778 (allocator)  - hands out a virtual IP to any device that asks

Devices never need to know your PC's IP. Each device broadcasts a
discovery request on its own local subnet; since your PC sits on every
one of those subnets simultaneously (the real LAN, plus each emulator's
virtual switch as its gateway), it replies from whichever interface is
correct for that specific device automatically -- the reply's source
address *is* the right hub IP for that device, no configuration involved.

Requires only the Python standard library. Run with:
    python hub_relay.py
"""

import asyncio
import json
import socket

PEERS: dict[str, tuple[str, int]] = {}   # virtual_ip -> (real_ip, real_port)
LEASES: dict[str, str] = {}              # client_id -> assigned virtual_ip

DISCOVERY_PORT = 7776
RELAY_PORT = 7777
ALLOC_PORT = 7778
DISCOVERY_MAGIC = b"LANBRIDGE_DISCOVER"
DISCOVERY_REPLY = b"LANBRIDGE_HUB"

SUBNET_PREFIX = "10.10.10."
_next_octet = 2                          # .1 is reserved for the hub itself


def allocate_ip(client_id: str) -> str:
    global _next_octet
    if client_id in LEASES:
        return LEASES[client_id]
    ip = f"{SUBNET_PREFIX}{_next_octet}"
    LEASES[client_id] = ip
    _next_octet += 1
    print(f"[alloc] {client_id} -> {ip}")
    return ip


class Discovery(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] discovery listening on UDP {DISCOVERY_PORT}")

    def datagram_received(self, data: bytes, addr):
        if data.strip() == DISCOVERY_MAGIC:
            print(f"[discover] request from {addr} -> replying")
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

        if PEERS.get(src_ip) != addr:
            print(f"[hub] peer {src_ip} -> {addr}")
        PEERS[src_ip] = addr

        last_octet = int(dst_ip.split(".")[-1])
        first_octet = int(dst_ip.split(".")[0])
        is_broadcast = last_octet == 255
        is_multicast = 224 <= first_octet <= 239

        if is_broadcast or is_multicast:
            for vip, paddr in PEERS.items():
                if paddr != addr:
                    self.transport.sendto(data, paddr)
        else:
            target = PEERS.get(dst_ip)
            if target:
                self.transport.sendto(data, target)


class Allocator(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] IP allocator listening on UDP {ALLOC_PORT}")

    def datagram_received(self, data: bytes, addr):
        try:
            req = json.loads(data.decode("utf-8"))
            client_id = str(req["client_id"])
        except Exception:
            return  # ignore malformed requests

        ip = allocate_ip(client_id)
        reply = json.dumps({"ip": ip, "hub_ip": SUBNET_PREFIX + "1"}).encode("utf-8")
        self.transport.sendto(reply, addr)


async def main():
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(Discovery, local_addr=("0.0.0.0", DISCOVERY_PORT))
    await loop.create_datagram_endpoint(Relay, local_addr=("0.0.0.0", RELAY_PORT))
    await loop.create_datagram_endpoint(Allocator, local_addr=("0.0.0.0", ALLOC_PORT))
    print("Hub running (fully zero-config). Press Ctrl+C to stop.")
    try:
        await asyncio.Event().wait()
    except asyncio.CancelledError:
        pass


if __name__ == "__main__":
    asyncio.run(main())