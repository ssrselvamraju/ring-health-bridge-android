"""Read-only BLE discovery for nearby Oura rings.

This performs advertisement discovery only. It does not connect, pair, write to a
GATT characteristic, create a key, or persist scan results.
"""

import argparse
import asyncio

from bleak import BleakScanner


OURA_SERVICE = "98ed0001-a541-11e4-b6a0-0002a5d5c51b"


async def scan(name_contains: str, timeout: float) -> None:
    discovered = await BleakScanner.discover(timeout=timeout, return_adv=True)
    candidates = []
    for device, advertisement in discovered.values():
        services = {uuid.lower() for uuid in advertisement.service_uuids or []}
        name = device.name or advertisement.local_name or ""
        if OURA_SERVICE not in services:
            continue
        if name_contains.lower() not in name.lower():
            continue
        candidates.append((advertisement.rssi, name, device.address))

    candidates.sort(reverse=True)
    if not candidates:
        print("No advertising Oura ring was found.")
        print("Put the ring on its charger, close the official Oura app, and retry.")
        return

    for rssi, name, address in candidates:
        print(f"{rssi:>5} dBm  {name}  ({address})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only Oura BLE scan")
    parser.add_argument("--name-contains", default="Oura")
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()
    asyncio.run(scan(args.name_contains, args.timeout))


if __name__ == "__main__":
    main()
