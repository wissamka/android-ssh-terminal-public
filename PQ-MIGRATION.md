# PQ migration: isolated protocol proof, Android rollout deferred

Evidence updated during the September 2026 issue #36 implementation. See the dated
[run record](tools/pq-probe/evidence.md) for the exact runtime, commands and results.

The existing Android SSHJ backend remains primary. No app Gradle dependency, engine,
authentication flow, prompt handling, session lifecycle, or signing provider is changed by
this worker's PQ commits. The functional opt-in flag and small `ProbeBackend` abstraction
are scoped to the [standalone JVM experiment](tools/pq-probe/README.md).
**There is no MINA runtime flag wired into the Android app yet.**

## Current evidence

| Gate | Status | Meaning |
| --- | --- | --- |
| Strict server identity before authentication | Tested on localhost JVM | Unknown/changed/revoked/malformed/wrong-port trust fails; hashed known_hosts succeeds; trust files remain unchanged |
| Explicit backend selection | Tested in executable harness | Default SSHJ; MINA requires opt-in; unknown values and unavailable hybrid capability fail without fallback |
| Hybrid exchange + auth + command | Tested on localhost JVM | ML-KEM768/X25519 and both sntrup761/X25519 names pass against a disposable MINA 2.19.0 server |
| Classical-only peer and bad identity | Tested on localhost JVM | No downgrade and no success result |
| OpenSSH interoperability | Not tested in this work | Historical pre-auth, accept-any-key remote probe is not accepted as authenticated evidence |
| Android DEX / R8 with MINA | Not tested in this work | An old dependency-only debug build is not current evidence |
| Android runtime and feature parity | Not implemented / not tested | MINA is not shipped by this change |

Apache's [2.19.0 Android guidance](https://github.com/apache/mina-sshd/blob/sshd-2.19.0/docs/android.md)
states that Android compatibility has not been thoroughly verified. It describes hooks for
home/current-directory resolution and cautions about provider and I/O compatibility. The
JVM harness uses NIO2 but does not establish that those hooks work on this app's supported APIs.

## Adapters the parent must own before an app opt-in is meaningful

Do not replace `SshEngine` wholesale with the probe. Its API only proves an authenticated
fixed command and intentionally lacks terminal, forwarding and jump semantics.

1. Extract an engine-neutral authenticated connection/chain contract with idempotent close,
   PTY dimensions and resize, shell stdin/stdout/stderr, session exit/error signals, and
   forwarding ownership. Wrap the current SSHJ implementation first and retain SSHJ as the
   default. `Chain.target` and consumers currently expose SSHJ types, so a flag alone cannot
   select a fully functional MINA backend.
2. Bridge MINA server-key callbacks to the current host-bound, queued trust prompts and atomic
   known-host store. Preserve revoked/hashed/port-qualified/certificate semantics, re-check
   concurrent trust changes, and reject changed keys. Do not substitute the library default
   verifier or the probe's temporary store for app trust decisions.
3. Bridge password and keyboard-interactive challenges to the parent's prompt concurrency
   work with per-request cancellation and secret lifetime limits. Add software-key and
   non-exportable AndroidKeyStore signing adapters. Do not globally pin BC as the signing
   provider; preserve hardware key signing. MINA's [provider registration guidance](https://github.com/apache/mina-sshd/blob/sshd-2.19.0/docs/security-providers.md)
   describes provider selection and one-time registration; JVM auto-detection is not proof
   of AndroidKeyStore compatibility.
4. Implement hop-by-hop jump channels with independent verification/authentication for every
   host, forwarding cleanup, keepalive/reconnect behavior and partial-chain failure cleanup.
   Coordinate with the parent lifecycle fixes; do not fork a competing connection owner.
5. Only after those adapters exist, wire a visible app backend setting/build flag whose
   absent value selects SSHJ. Explicit MINA selection must reject unavailable capabilities
   and unsupported features clearly; it must never silently weaken host trust or negotiate
   classical KEX while claiming PQ. Existing active sessions must keep their selected owner.
6. Run Android API/device tests for the three selected hybrids, all authentication methods,
   terminal PTY/shell/resize, jump hosts, reconnect, forwarding, cancellation and shutdown.
   Verify minified release/R8 and provider coexistence. Keep the opt-in off until those gates
   pass. Perform fresh authenticated OpenSSH tests against a disposable local server too.

This is a concrete, reproducible security improvement over the recovered probe, and partial
progress on #36. It does not close the Android backend migration or justify a shipped-PQ claim.
