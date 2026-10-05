"""Connect to an already Windows-paired Oura ring without advertisement scan."""

import argparse
import asyncio
import sys
from pathlib import Path

from bleak import BleakClient


WORKSPACE = Path(__file__).resolve().parent.parent
UPSTREAM_TOOLS = WORKSPACE / "third_party" / "open_oura" / "tools"
sys.path.insert(0, str(UPSTREAM_TOOLS))

import oura_protocol as protocol  # noqa: E402


async def verify(
    address: str,
    key_path: Path,
    capture_path: Path,
    timeout: float,
    install_key: bool,
) -> None:
    key = bytes.fromhex(key_path.read_text(encoding="utf-8").strip())
    if len(key) != 16:
        raise ValueError("The Oura application key must be exactly 16 bytes")

    print("Connecting directly to the Windows-paired ring...", flush=True)
    async with BleakClient(address, timeout=timeout) as client:
        print(f"connected={client.is_connected} mtu_size={client.mtu_size}", flush=True)
        if install_key:
            print("Installing the locally generated application key...", flush=True)
            await protocol.set_auth_key(client, key, 0.75, capture_path)
        await protocol.authenticate(client, key, 0.75, capture_path)
        for name in ("battery",):
            command = protocol.COMMANDS[name]
            await protocol.transact(
                client,
                command.request,
                0.75,
                capture_path,
                name,
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=45.0)
    parser.add_argument(
        "--install-key",
        action="store_true",
        help="Install the key first; use only while the ring is factory reset.",
    )
    args = parser.parse_args()
    asyncio.run(
        verify(
            args.address,
            args.key_file,
            args.capture,
            args.timeout,
            args.install_key,
        )
    )


if __name__ == "__main__":
    main()
