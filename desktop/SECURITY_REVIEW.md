# Provisioning security notes

Scope: `open_oura` revision
`c5106bd5674dd98f07954b96dff9e16c8fe26f06`, its
`tools/oura_protocol.py` utility, and this directory's wrappers.

The wrapper exposes advertisement scanning, an explicitly confirmed factory-reset
pairing path, and authenticated safe reads. It does not pass upstream's
`--include-danger` flag. Key and capture paths are redirected outside the source
checkout and protected with restricted Windows ACLs.

This is a source review, not a security proof. The separately downloaded upstream
tree includes unrelated research utilities capable of network access, firmware
downloads, token handling, raw operations, and other state changes. They are not
dependencies of this wrapper and are not authorized by these instructions.

Risks remain: a compromised user account or administrator can read local secrets;
BLE and terminal output can expose device identifiers; dependencies can be
compromised; and factory reset is destructive. Inspect the exact upstream revision,
use full-disk encryption, and never share provisioning output publicly.
