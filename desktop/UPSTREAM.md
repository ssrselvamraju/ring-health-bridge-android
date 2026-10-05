# Pinned upstream

The reviewed provisioning implementation is:

- Repository: `https://github.com/Th0rgal/open_oura`
- Commit: `c5106bd5674dd98f07954b96dff9e16c8fe26f06`
- Commit date: 2026-07-08
- License declared by the Cargo workspace: MIT
- Note: this pinned tree declares MIT in `Cargo.toml` but has no standalone
  `LICENSE` file; see the repository-level `THIRD_PARTY_NOTICES.md`.

Do not update the downloaded copy before reviewing the new diff. The security
assessment in this workspace applies only to the commit above and the exact Python
packages installed in its isolated `.venv`:

- `bleak==0.22.3`
- `pycryptodome==3.23.0`
- `winrt-runtime==2.3.0`
- `winrt-Windows.Devices.Bluetooth==2.3.0`
- `winrt-Windows.Devices.Bluetooth.Advertisement==2.3.0`
- `winrt-Windows.Devices.Bluetooth.GenericAttributeProfile==2.3.0`
- `winrt-Windows.Devices.Enumeration==2.3.0`
- `winrt-Windows.Foundation==2.3.0`
- `winrt-Windows.Foundation.Collections==2.3.0`
- `winrt-Windows.Storage.Streams==2.3.0`
