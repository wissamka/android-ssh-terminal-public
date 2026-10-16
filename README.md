# SSH Term

A native Android SSH client optimized for real terminal work from a phone — administering VPS
instances, running interactive tools (vim, htop, tmux, docker, journalctl) — not a toy demo
terminal.

**Experimental software.** This repository is available for source review and experimentation;
public source availability does not establish production readiness or approval for a paid release.
The app handles server credentials. Full functional validation of the optimized APK, physical-device
and OEM behavior, and Play billing/account acceptance remain open. See the
[implementation and acceptance ledger](VALIDATION.md),
[minified-runtime validation limits](spec/minified-audit-runtime.md), and
[production checklist](PRODUCTION.md) before distributing a production build.

## Features

- **Correct, fast terminal emulation** — vendored from Termux's battle-tested Apache-2.0
  `terminal-emulator`/`terminal-view` libraries (alt-screen, scroll regions, 256-color/truecolor,
  resize/reflow). The full upstream VT100 test suite (145 tests) runs against the vendored code.
- **Connection profiles** with first-class **jump-host chaining** (ProxyJump equivalent —
  a profile can hop through another profile, recursively). Passwords are **never stored**;
  password-auth profiles prompt at connect time, every time.
- **SSH key management**, with storage and protection status surfaced in the UI:
  - *Android Keystore*: EC-P256 / RSA-3072/4096 private keys are non-exportable through the app.
    Hardware backing depends on the device and key; the app inspects each key and reports
    StrongBox, TEE, unspecified hardware, software backing, or unknown protection. Newly generated
    keys require fresh biometric authorization for each signing operation, bound to that operation's
    CryptoObject rather than a recent-authentication time window. Existing keys are checked for
    per-operation policy before signing; device-credential fallback is not promised for these operations.
  - *Software-encrypted*: Ed25519 and imported keys (OpenSSH v1 / PEM / PKCS8 / PuTTY) are
    AES-256-GCM encrypted at rest. New generation/import and existing legacy files use the legacy
    Keystore master key until explicitly upgraded; app-mediated authorization does not make that
    legacy storage cryptographically require authentication for each decrypt. The explicit V2 upgrade
    adds per-operation biometric authorization to decryption. It retains legacy recovery ciphertext
    until a separately authorized finalization succeeds, and the UI identifies legacy or pending
    protection. No automatic upgrade or device-credential fallback for V2 is implied. See the
    [software-storage upgrade design](spec/issue-28-software-wrapper.md).
  - One-tap public-key copy for pasting into `authorized_keys`.
- **Frecency-ranked command autocomplete**: the input bar's ▲/▼ drive a suggestion dropdown
  ranked by frequency × recency-decay (the `z`/autojump/Firefox-awesomebar family), scoped
  per profile with a global fallback tier and a seed dictionary of common VPS-admin commands.
  Selecting a suggestion edits it into the line — it never auto-executes.
- **Terminal keyboard**: in-app extra-keys row (ESC, sticky CTRL/ALT with tap-to-arm /
  double-tap-to-lock, TAB, arrows, HOME/END/PGUP/PGDN, common symbols). Sticky modifiers apply
  to both the soft keyboard and physical/Bluetooth keyboards.
- **Sessions that survive**: multiple simultaneous sessions as tabs, kept alive by a foreground
  service; network flaps (wifi↔cellular) reconnect automatically with scrollback preserved;
  aggressive SSH keepalives detect dead links fast; contextual battery-optimization exemption
  prompt at first connect.
- **Security posture**: strict TOFU host-key verification with a loud blocking warning on key
  mismatch (never silently accepted, stored entry never silently replaced), `FLAG_SECURE` on the
  window (no app-switcher thumbnails/screenshots of your session), app-level lock on cold open
  with background timeout, bundled full BouncyCastle (modern kex/ciphers).
- **Local fallback shell**: `/system/bin/sh` over plain pipes. Deliberately minimal — no PTY, so
  no job control and full-screen programs won't render locally. The primary path is SSH, where
  the PTY is allocated server-side via the protocol (`pty-req`), giving full fidelity.
- Bundled JetBrains Mono Nerd Font (box-drawing + powerline glyph coverage).

## Building

Requires JDK 17+, Android SDK platform 37 + build-tools 36.

```
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # unsigned; sign per your release process
./gradlew :terminal-emulator:testDebugUnitTest   # vendored VT100 test suite
```

## Architecture

```
app/                     Kotlin, Compose (M3), MVVM + repositories, Hilt, Room
├── core/ssh/            sshj engine (jump chains, keepalive), TOFU verifier,
│                        reconnecting SSH-backed TerminalSession
├── core/keys/           Keystore keygen + sshj KeyProvider bridge, AES-GCM file store,
│                        OpenSSH key codec, import (OpenSSH/PEM/PKCS8/PuTTY)
├── core/localshell/     ProcessBuilder fallback shell (no PTY, documented limitation)
├── data/                Room: profiles, key metadata, command history
├── service/             Foreground service keeping SSH sockets alive
└── ui/                  terminal screen (tabs/extra keys/autocomplete), profiles, keys,
                         app lock
terminal-emulator/       vendored (see Licensing)
terminal-view/           vendored (see Licensing)
```

Design notes:

- `TerminalSession` (vendored) was refactored into an abstract transport-agnostic class; the
  Termux native `forkpty()` JNI layer was removed entirely. SSH and local-shell transports
  subclass it.
- sshj's BouncyCastle auto-registration is disabled at startup (`SshTermApplication`) so JCA
  *delayed provider selection* routes signing with non-exportable AndroidKeyStore keys to the
  Keystore provider — this is what makes hardware-backed keys work with sshj at all.

## Licensing

The application code is **Apache-2.0** (see `LICENSE`). All dependencies are permissive:

- `terminal-emulator/`, `terminal-view/`: vendored from
  [termux-app](https://github.com/termux/termux-app) @ 401bbe54, which licenses these two
  libraries under **Apache-2.0** as an explicit exception to the rest of that repo's GPLv3.
  Only these two modules were taken; the GPLv3 app shell and native PTY shim were not.
  Local modifications are documented in each module's `NOTICE.md` and in-file notices.
- [sshj](https://github.com/hierynomus/sshj) — Apache-2.0. BouncyCastle — MIT-style.
- JetBrains Mono (Nerd Fonts patched) — SIL OFL 1.1 (`FONT_LICENSE-OFL.txt`).

No GPL/LGPL code is included, by design (F-Droid / Play publishing without copyleft
entanglement).

## Known limitations (v1)

- Local shell has no PTY (by scope decision): job control and full-screen apps don't work
  locally. A ~150-line `forkpty()` JNI shim is the documented path if ever wanted.
- No system-wide IME, no bundled Linux userland, no SFTP browser UI, no Mosh — deliberate
  non-goals for v1.
- `ssh-rsa` (SHA-1) signatures are not offered for hardware RSA keys — only `rsa-sha2-256/512`.
  Ancient servers that never learned RFC 8332 should use a software key instead.
