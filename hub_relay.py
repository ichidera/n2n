#!/usr/bin/env python3
"""
n2n-lite hub relay
------------------
Runs on your Windows PC (the hub). Every edge device's Android app
sends its raw IP packets here over UDP. This relay:
  - Learns which virtual IP belongs to which real UDP address
    automatically (from the source IP field inside each packet).
  - Forwards unicast packets straight to the intended peer.
  - Floods broadcast/multicast packets to every OTHER connected peer,
    which is the piece a normal router/VPN can't do and is exactly
    what LAN game discovery (Mini Militia, etc.) needs.

Requires only the Python standard library. Run with:
    python hub_relay.py
"""

import asyncio
import socket

PEERS: dict[str, tuple[str, int]] = {}  # virtual_ip -> (real_ip, real_port)
LISTEN_PORT = 7777


def ipv4_str(raw: bytes) -> str:
    return socket.inet_ntoa(raw)


class Hub(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport
        print(f"[hub] listening on UDP {LISTEN_PORT}")

    def datagram_received(self, data: bytes, addr):
        if len(data) < 20:
            return  # not a valid IPv4 packet

        src_ip = ipv4_str(data[12:16])
        dst_ip = ipv4_str(data[16:20])

        # Learn/update this peer's real address
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
            # else: unknown destination yet, drop silently


async def main():
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(
        Hub, local_addr=("0.0.0.0", LISTEN_PORT)
    )
    print("Hub relay running. Press Ctrl+C to stop.")
    try:
        await asyncio.Event().wait()
    finally:
        transport.close()


if __name__ == "__main__":
    asyncio.run(main())
