# Third-party notices

## open_oura

- Project: `open_oura`
- Repository: <https://github.com/Th0rgal/open_oura>
- Pinned revision: `c5106bd5674dd98f07954b96dff9e16c8fe26f06`
- Declared license: MIT (`license = "MIT"` in the pinned Cargo workspace)

Ring Health Bridge adapted protocol framing, AES nonce authentication, history
request/response parsing, selected event-body decoders, and associated known-answer
fixtures from `open_oura`. Relevant project files include:

- `android/app/src/main/java/dev/local/ourahealthbridge/protocol/OuraAuthentication.kt`
- `android/app/src/main/java/dev/local/ourahealthbridge/protocol/OuraProtocol.kt`
- `android/app/src/main/java/dev/local/ourahealthbridge/protocol/OuraHistory.kt`
- `android/app/src/main/java/dev/local/ourahealthbridge/protocol/OuraEventDecoder.kt`
- the corresponding protocol tests under `android/app/src/test/`

The optional desktop provisioning wrapper invokes a separately downloaded copy of
the pinned upstream Python tool. Upstream source is not vendored here.

At the pinned revision the Cargo workspace declares MIT, but the revision contains
no standalone `LICENSE` file or explicit copyright line to reproduce. No upstream
copyright notice was removed. The MIT permission notice governing this project's
adapted code is included in this repository's [`LICENSE`](LICENSE). Consult the
upstream repository before updating the pin.

## Gradle wrapper

The Gradle wrapper scripts retain their original 2015 copyright notice and
Apache-2.0 license header. See the headers in `android/gradlew` and
`android/gradlew.bat`.

## Android dependencies

Build dependencies are resolved from Google Maven, Maven Central, and the Gradle
Plugin Portal. Their licenses are not relicensed by this project's MIT License.
Review the resolved dependency metadata before distributing binaries.
