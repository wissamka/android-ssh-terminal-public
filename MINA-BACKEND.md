# Operational MINA backend — implementation and test handoff

Updated **2026-09-13 05:09 UTC**. This supersedes the Android-implementation status of the
older standalone PQ probe documents. The probe remains a separate reproducible experiment.

## Select the backend

`SshEngine` reads `BuildConfig.SSH_BACKEND`. The Gradle property `sshBackend` accepts exactly
`sshj` (default) or `mina-pq`; other values fail configuration. For parent-orchestrated builds:

```sh
./gradlew :app:assembleDebug -PsshBackend=mina-pq
```

The no-property build retains the original SSHJ connection/authentication path. There is no
fallback from selected MINA to SSHJ or from hybrid MINA to classical KEX. MINA advertises only
ML-KEM768/X25519 and the two sntrup761/X25519 protocol names, checks runtime availability,
and validates the actual negotiated name after authentication. The terminal prints that name
only after the authenticated connection's shell channel opens. This is a build flag, not a
new runtime settings toggle.

## Production implementation

- `SshTransportConnection`, `SshShell`, and `SshForward` isolate the backend-specific API.
  The existing terminal reconnect/backoff, cancellation and `LifecycleResources` owner remain
  in control. Shell resources are registered explicitly; EOF processing waits at most two
  seconds for the exit status so a clean exit is not mistaken for a dropped connection.
- SSHJ is wrapped by `SshjTransportConnection`; its engine signing and interactive-auth
  handling are preserved. Parent cancellation handling and deferred-key changes were rebased
  into this branch before final testing.
- MINA uses explicit NIO2 and app-private path resolvers. It disables inherited SSH config,
  default disk identities, agents and unsolicited interaction. Server keys are converted
  through SSH wire encoding and verified by the **existing** `TofuKnownHostsVerifier` against
  the original host/port, including when connecting through a local jump tunnel. Unknown or
  changed trust never silently succeeds.
- `MinaSigning` uses the unchanged `AuthorizedSignature` and `SigningAuthorizer` boundary.
  Verification delegates to MINA; private signing delegates to the existing SSHJ signature
  encoder. Thus RSA/EC/Ed25519 software keys and `DeferredSigningKey` use the same fresh
  authorization before material loading. Per-operation hardware signing supplies the existing
  exact initialized `CryptoObject` operation via that shared boundary. Unsupported per-operation
  key types are refused before connecting. No BC-wide signing-provider pin is introduced.
- Public-key authentication has the parent's **210-second** transport budget for fresh approval
  plus protected key loading. Password/interactive authentication uses 120 seconds; each
  challenge uses the parent's existing 90-second limit. Denial closes the MINA session and
  becomes fatal, rather than retrying signatures or reclassifying cancellation as a network flap.
- PTY allocation, resize, combined terminal output, stdin, exit status, local and remote forwards,
  nested jump hops and cleanup have real localhost coverage. Jump transports use short-lived
  loopback-bound forwarding handles owned by the target connection; every hop authenticates
  and verifies its original destination separately. These internal listeners live with the chain.
- Remote-to-local forwarding is restricted to destinations of registered `-R` rules. Unsolicited
  direct-tcpip, agent/X11 requests and server-requested local listeners remain denied.
- Keepalives request replies every 15 seconds with at most three unanswered requests. Transport
  interruption remains retryable; explicit authentication, trust and KEX failures are fatal.

MINA 2.19.0 is an app implementation dependency; no GPL code or new private-key storage format
is introduced. R8 keep rules cover its reflective factories. Android/R8 execution is a parent gate.

## Exact standalone evidence

On Android Studio OpenJDK **21.0.10**, Kotlin **2.4.0**, MINA **2.19.0**, BC **1.86**, SSHJ **0.40.0**,
using Android API 37 stubs for compilation only:

```sh
python3 tools/mina-backend/run_tests.py \
  --deps /private/tmp/sshterm-pq-deps \
  --java-home '/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  --android-jar "$ANDROID_HOME/platforms/android-37.0/android.jar"
```

```text
JUnit version 4.13.2
.........................
Time: 4.706

OK (25 tests)
```

This compiles the actual `SshEngine`, both backends, `PortForwardManager`,
`SshTerminalSession`, shared authorization code and terminal-emulator Java code. The generated
BuildConfig fixture mirrors the default `sshj` value; tests select MINA explicitly. It runs
`SshEngineProtocolTest`, `MinaBackendProtocolTest`, and `SigningAuthorizationTest`. The runner
uses existing cached artifacts and no Gradle process or network fetch.

The shared protocol contract proves live RSA/EC/BC-Ed25519 signing, fresh reconnect approval,
password and multi-prompt interactive authentication, deny-before-sign, changed-host rejection,
per-hop signing, partial-chain cleanup, PTY/resize/exit and both forward directions on both
backends, and rejection of a classical-only peer. Deferred RSA/EC/Ed25519 each verify
`approve, load, approve, load` across two connects and `deny` with no load afterward.

## Android adapter and remaining acceptance gates

`MinaProtocolContract` lives in `app/src/protocolTest/java` and is compiled into both test
source sets. JVM and Android adapters inherit exactly the same tests. The Android adapter,
`dev.wissam.sshterm.core.ssh.MinaBackendDeviceTest`, uses real app initialization and writes
only disposable known-host fixtures into the app cache. All servers bind `127.0.0.1` with
new in-memory software keys and fixed Java test shells; no user server, key or agent is used.

The parent can run the class through the normal instrumentation filter after compiling the
app/test APKs. This worker did **not** run Gradle, R8, adb or instrumentation. The tests are
implemented, not evidence of an Android pass yet.

Remaining gates: Android API/runtime and minified-release execution; physical AndroidKeyStore
biometric/CryptoObject flows; physical network-change reconnects and background lifecycle;
fresh authenticated OpenSSH interoperability; supported-device performance. JVM repeated-connect
coverage verifies fresh auth/resource reuse but does not simulate Android network switching.
Do not mark those gates complete or describe the backend as fully device-validated from the
standalone result. The Android emulator/API compilation and runtime results belong in the
parent's integrated release evidence.

## Integrated acceptance update

The actual Android protocol contract passed all 11 cases on API 36; the final combined core
suite passed 36 cases. The later review removed jump-host listening sockets in favor of
internal direct-tcpip channels, handles signal-only remote termination without reconnect,
and separates host approval from the full signing budget. Deterministic regressions cover
cancellation before/during attachment, throwing close callbacks, and expiry at/after trust
completion. Both follow-up findings were independently re-reviewed as addressed.

Final app JVM suite: 166 passing tests; terminal suite: 147. Actual strong-biometric EC/RSA
and V2 storage tests passed separately on the debug audit APK. These do not prove physical
hardware security, real network handoff/endurance, live OpenSSH interoperability on every
server, or full optimized-app behavior. Minified release builds pass; the R8 instrumentation
limitation and launch-only smoke contract are documented in spec/minified-audit-runtime.md.
Current evidence and issue acceptance are in VALIDATION.md and PR #55.
