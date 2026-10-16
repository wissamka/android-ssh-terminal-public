# Strict localhost PQ experiment

This is an **isolated JVM experiment**, not an Android backend rollout. It is deliberately
not included by the Android root settings or app dependencies. The app still uses its existing
SSHJ engine. The operational backend flag below applies **only to this harness**.

## What it does

`LocalProbe` starts an in-process MINA SSH server bound exclusively to `127.0.0.1` on an
OS-assigned port. Host and client RSA-2048 keys are newly generated in memory for each run.
Only the fixture's public host key goes into a temporary `known_hosts` file. No private keys
are written, and no user SSH configuration, identities, agents, passwords or VPS are used.
The fixture implements one fixed command in Java; it never launches an OS shell.

`ProbeBackend.select` actually routes the connection:

| Flag | Result |
| --- | --- |
| Absent, or `--backend=sshj` | SSHJ 0.40.0; strict authenticated classical baseline |
| `--backend=mina-pq --enable-experimental-mina` | MINA 2.19.0; exactly the selected hybrid KEX |
| MINA without opt-in; unknown backend/KEX | Error before connecting; no SSHJ fallback |

The MINA client uses `KnownHostsServerKeyVerifier` with a reject-all fallback and an explicit
reject-changed-key callback. It never accepts new keys or updates trust. Known-host matching
includes the nonstandard port. The client disables inherited SSH config, filesystem identities,
agents and interactive authentication, uses NIO2, and supplies only the disposable public-key
identity. Host and user RSA signatures are restricted to SHA-2. An `ssh-rsa` **public key file
format** is not permission to negotiate an SHA-1 signature.

Success is printed only after host verification, successful public-key authentication, an exact
hybrid-name match, a successful channel command exit, and the expected response. Each MINA
run advertises one hybrid, so a classical-only server fails instead of downgrading. Connection,
authentication and command waits are bounded. The fixture, channels, sessions and clients are
closed on success and failure.

The three explicit hybrid choices are:

- `mlkem768x25519-sha256` (default for an explicitly selected MINA run)
- `sntrup761x25519-sha512`
- `sntrup761x25519-sha512@openssh.com`

These protect key exchange; the fixture's RSA authentication keys are **not PQ signatures**.

## Run without Gradle

Requires Python 3 and a JDK 17 or newer. The standalone runner verifies every binary against
`dependencies.lock.json`, uses a supplied directory or the existing Gradle artifact cache,
and only downloads public Maven artifacts when `--fetch` is explicitly supplied.
It compiles into a temporary directory and runs no Gradle process.

From the repository root (replace the JDK path for your machine):

```sh
python3 tools/pq-probe/run.py --deps /private/tmp/sshterm-pq-deps \
  --java-home '/Applications/Android Studio.app/Contents/jbr/Contents/Home' test

# Actual default SSHJ baseline:
python3 tools/pq-probe/run.py --deps /private/tmp/sshterm-pq-deps \
  --java-home '/Applications/Android Studio.app/Contents/jbr/Contents/Home' probe

# Explicit hybrid opt-in; --kex can select either sntrup spelling above:
python3 tools/pq-probe/run.py --deps /private/tmp/sshterm-pq-deps \
  --java-home '/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  probe --backend=mina-pq --enable-experimental-mina --kex=mlkem768x25519-sha256
```

The Java process needs permission to bind loopback sockets. Do not replace failed loopback
permission checks with a remote target. The CLI has no host, username or private-key arguments.
`Request.host()` is fixed to a literal loopback IP, avoiding DNS redirection.

A separate Gradle project is also supplied for parent-orchestrated integration:
`./gradlew -p tools/pq-probe test`. That route has not been run in this worker. The SHA-256
lock applies to `run.py`; the standalone Gradle project uses pinned direct dependency versions.

## Tests and limitations

See [evidence.md](evidence.md) for the actual run and cases, and
[the migration gates](../../PQ-MIGRATION.md) for remaining app adapters.
The local server is MINA too: this is a live protocol test, **not fresh OpenSSH interoperability
or Android runtime evidence**. No assertion of release readiness follows from these results.

## Source and dependency notes

The recovered `df606c9` probe used an accept-any-host verifier and only waited for the
pre-authentication state. Its remote-host output cannot establish authenticated PQ security.
That probe, its remote target, and its app dependency injection were not copied into this change.

- [Apache downloads](https://mina.apache.org/downloads-sshd.html) identifies SSHD 2.19.0 as the latest release at verification time.
- [Versioned client setup](https://github.com/apache/mina-sshd/blob/sshd-2.19.0/docs/client-setup.md) documents the unsafe default verifier, filesystem identity defaults, and explicit authentication. This harness overrides those defaults.
- [Versioned supported standards](https://github.com/apache/mina-sshd/blob/sshd-2.19.0/docs/standards.md) lists hybrid algorithms and their Bouncy Castle requirement. Availability is also checked and exercised at runtime.
- Exact `KnownHostsServerKeyVerifier`, `BuiltinDHFactories`, `ClientSession`, `ClientBuilder`, and `ServerBuilder` APIs were checked in the Maven Central 2.19.0 source jars before compilation.

No GPL implementation or vendored crypto algorithm is introduced. MINA and SSHJ are Apache-2.0;
Bouncy Castle and SLF4J use permissive licenses. JUnit/Hamcrest are test dependencies. This
experiment adds no libraries to the shipped APK. Upstream license notices remain in the fetched
jars; the binaries are not committed. The lock records the bytes used, not a substitute for
upstream release-signature verification or a complete shipped-app license audit.
