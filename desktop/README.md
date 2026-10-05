# Optional Gen 3 desktop provisioning

These Windows helpers invoke the pinned `open_oura` Python BLE utility to provision
a Gen 3 Horizon without an Oura account or subscription. The upstream checkout is
intentionally not vendored.

## Before proceeding

Provisioning requires a deliberate factory reset. A reset erases data held on the
ring and breaks its existing official-app relationship. To return to the official
app later, factory-reset again and complete the official pairing flow.

The generated ring key and capture are plaintext secrets stored below
`%LOCALAPPDATA%\OuraHealthBridge` with Windows ACLs restricted to the current user
and SYSTEM. Do not put them in source control, cloud storage, issue reports, or
screenshots. Full-disk encryption is recommended.

## Reproducible setup

1. Clone <https://github.com/Th0rgal/open_oura> into
   `third_party/open_oura`.
2. Check out exactly `c5106bd5674dd98f07954b96dff9e16c8fe26f06`.
3. Create `third_party/open_oura/.venv` and install the exact packages recorded in
   [`UPSTREAM.md`](UPSTREAM.md).
4. Review the upstream diff and its scripts before running them.

Commands from the repository root:

```powershell
# Advertisement-only discovery: no connection or ring write.
powershell -ExecutionPolicy Bypass -File .\desktop\oura_provision.ps1 -Action Scan

# Destructive prerequisite: only after an intentional factory reset.
powershell -ExecutionPolicy Bypass -File .\desktop\oura_provision.ps1 `
  -Action Pair -ConfirmFactoryReset

# Authenticated, read-only verification.
powershell -ExecutionPolicy Bypass -File .\desktop\oura_provision.ps1 -Action Verify

# Stream the key into an installed debug build's private sandbox.
powershell -ExecutionPolicy Bypass -File .\desktop\push_key_to_android.ps1
```

The Android import bridge exists only in the debug source set. Release builds do
not accept staged keys.
