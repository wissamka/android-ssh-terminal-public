# Source-publication validation — 2026-10-16

Three independent reviews covered lint/release quality, deep security, and code correctness. The resulting fixes reject malformed terminal capability queries, apply password IME settings, isolate service teardown ownership, preserve cleanup on journal failures, make key registration cancellation-safe in both editors, and honor remote SSH exit signals.

## Checks completed

| Check | Result |
|---|---|
| App JVM tests | 180 passed |
| Terminal emulator JVM tests | 151 passed |
| Android security/input tests | 17 passed |
| Android core/protocol/migration/lifecycle tests | 38 passed |
| Actual per-operation biometric tests | 6 passed; 10 separate operation approvals and 3 cancellations |
| Standalone hybrid probe | 11 passed |
| Standalone transport runner | 35 passed (overlapping protocol coverage, not 35 additional unique cases) |
| No-enrollment denial tests | 2 passed on the baseline integration; cryptographic policy unchanged in follow-up fixes |
| Full debug/release Android lint, all three modules | No errors; app 40, terminal-view 22, terminal-emulator 1 warning per variant |
| Debug, instrumentation and optimized release builds | Passed |
| Optimized SSHJ and MINA audit APK launch | Both passed; stable process for 10 seconds and no matching fatal crash |
| OSV runtime dependency scan | 194 resolved Maven components; no matched advisories on 2026-10-16 |

All Android runs used disposable API 36 emulators and the separate audit package. No personal device, real server credentials, or production billing account was used. The initial emulator-startup ANR and test harness/assertion failures were preserved in private validation records; applicable subsequent tests passed without disabling them.

## Publication privacy

This is a fresh repository with a clean initial source commit and reviewed follow-up changes. Old Git history, pull-request metadata, internal audit discussions and raw development logs were not copied. Personal home paths were removed and test private keys are generated ephemerally. Commit identity uses the maintainer's GitHub handle and GitHub noreply address. Application/package identifiers and upstream license/copyright attribution remain intact.

Gitleaks 8.30.1 checked the source snapshot. Its remaining private-key match is the reviewed OpenSSH encoder's literal format template containing the variable `$b64`, not key material. Pattern/manual review found no personal email, personal workstation path, private server address or actual credential in the selected source. These checks are bounded detection, not a guarantee of absence of every possible vulnerability or personal-data category.

## Limits

This is experimental source publication, not a production or paid APK certification. Optimized launch checks do not establish full optimized SSH/key/billing functionality. Physical Bluetooth/IME, StrongBox/TEE, OEM/background/network endurance, broad real-server interoperability, live Play transactions, and independent external security acceptance remain open. See [production acceptance](PRODUCTION.md).
