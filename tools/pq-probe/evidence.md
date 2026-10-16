# Local JVM protocol evidence — 2026-09-13 04:39 UTC

Implementation commit: `9c79d9ff15e253f4e33966335f208253e38f5e76`.
Branch: `fix/pq-release`. Worker clone: `/private/tmp/sshterm-worker-pq`.

Runtime: macOS host, Android Studio's OpenJDK **21.0.10**, compiled with `javac --release 17`.
Libraries: MINA SSHD **2.19.0**, Bouncy Castle **1.86**, SSHJ **0.40.0**, SLF4J **2.0.17**.
JUnit **4.13.2**. Exact binary SHA-256 values are in `dependencies.lock.json`.

Only loopback sockets were used. Fixtures generated their own ephemeral RSA keys and temporary
known-host files. No user private keys, accounts, remote hosts, SSH config, or agents were used.
All tests ran using the standalone runner; no Gradle, adb, device, VPS, or push command ran.

## Commands actually run

From the worker clone:

```sh
python3 tools/pq-probe/run.py --deps /private/tmp/sshterm-pq-deps \
  --java-home '/Applications/Android Studio.app/Contents/jbr/Contents/Home' test
```

Final test output:

```text
JUnit version 4.13.2
.......verified hybrid=mlkem768x25519-sha256 authenticated=true command=true
verified hybrid=sntrup761x25519-sha512 authenticated=true command=true
verified hybrid=sntrup761x25519-sha512@openssh.com authenticated=true command=true
....
Time: 2.595

OK (11 tests)
```

| Regression | Observed result |
| --- | --- |
| Each of three advertised hybrid names | Exact KEX selected, host trusted, user authenticated, command output verified |
| No backend flag | Real SSHJ connection, `curve25519-sha256`, authenticated command |
| Disabled MINA, misspelled backend, classical/absent KEX | Rejected before connection selection |
| Unknown, changed, revoked, wrong-port, malformed known_hosts | Connection rejected; zero server authentication attempts and commands |
| Hashed port-qualified known_hosts | Authentication succeeds; file bytes unchanged |
| Wrong client identity | Auth attempted but fails; no command/success evidence |
| Classical-only server | Hybrid negotiation fails; no authentication or command |
| Missing Bouncy Castle (fresh subprocess) | Requested sntrup capability unavailable; no success evidence or fallback |
| Missing trust file | Request rejected before connection |
| Nonzero command exit or wrong response | No success evidence despite prior authentication |
| Remote target CLI argument | Rejected; no remote endpoint option exists |

An initial sandbox run could not bind loopback sockets. The successful runs above used the
approved local-network execution permission. No remote workaround was used.

Both executable routes were also exercised:

```sh
python3 tools/pq-probe/run.py --deps /private/tmp/sshterm-pq-deps \
  --java-home '/Applications/Android Studio.app/Contents/jbr/Contents/Home' probe
```

```text
backend=sshj kex=curve25519-sha256 authenticated=true command=verified scope=localhost-jvm-only
```

```sh
python3 tools/pq-probe/run.py --deps /private/tmp/sshterm-pq-deps \
  --java-home '/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  probe --backend=mina-pq --enable-experimental-mina --kex=mlkem768x25519-sha256
```

```text
backend=mina-pq kex=mlkem768x25519-sha256 authenticated=true command=verified scope=localhost-jvm-only
```

## Scope of proof

These are authenticated **MINA-to-MINA localhost JVM** hybrid protocol tests, with an SSHJ
classical baseline. They are not OpenSSH interoperability results, Android runtime results,
PTY/jump/forwarding/reconnect/signing-adapter parity, performance evidence, or release-build
verification. The standalone Gradle project has not been run. No MINA backend is enabled in
the app, and issue #36 remains partially implemented.

The [migration document](../../PQ-MIGRATION.md) lists the parent-owned integration adapters.
The [README](README.md) links the exact upstream docs reviewed and explains why the historical
accept-any-key, pre-authentication probe is insufficient evidence.
