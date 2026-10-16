# Operator-assisted per-operation Keystore instrumentation

These are NEW TESTS ONLY. No production code/build files were changed. No adb, Gradle, enrollment,
PIN setting, device modification or runtime execution was performed by the worker.

## What is feasible

Use the parent's explicitly disposable `emulator-5580` (API 36) and separate `.audit` application UID.
First run `UnenrolledKeystoreRuntimeTest` while the emulator has no PIN or enrolled fingerprint. Then
parent/operator may configure a PIN and fingerprint through the emulator's Android Settings and run
`ApprovedKeystoreRuntimeTest`. Do not run the old no-credential SecurityRuntimeFixture suite after
changing enrollment: that suite intentionally rejects secured devices.

The positive suite FAILS if no strong biometric is available. It does not assume/skip or call fake
success. Each APPROVE prompt must receive an actual successful emulator fingerprint event, producing
Android's operation-specific auth token. Tests invoke production `showAuthPrompt`, whose callback
checks exact Signature/Cipher identity. The success path then executes the actual JCA operation.

This proves emulator per-operation cryptographic behavior and the production prompt/signing/storage
boundary when run successfully. It does not prove physical StrongBox/TEE backing, navigation/AppLock
policy, real-server SSH interoperability, enrollment-change recovery or power-loss durability.

## Isolation prerequisites

- Build/install the existing audit variant and its Android test APK using the parent's pipeline
  (`auditInstall=true`). No build/dependency changes are required by these tests.
- Use ONLY the disposable emulator; guard requires `securityAuditDisposable=true`, the exact target
  package argument, `.audit` suffix and emulator hardware identity. Never run on the owner's phone.
- Keep the emulator screen unlocked and awake. Do not run other instrumentation or use app key
  generation/import while these tests run. Complete the real “Unlock SSH Term” prompt on cold open before approving any APPROVE prompt. Tests wait for the actual app-lock callback; they do not call appLock.unlock or manufacture success.
- Storage tests require BOTH fixed wrapper aliases (`sshterm-key-file-master` and
  `sshterm-key-file-auth-v2`) to be absent in this disposable UID. If either exists the test fails
  before reserving either alias and will NOT delete it. Parent must choose a fresh disposable audit
  installation/snapshot/UID; do not weaken the guard or erase an existing installation's keys.
- Each test creates its own random cache directory. Production generation creates random signing
  aliases; only those returned to the test are deleted. Wrapper aliases are cleaned up only when
  the test proved them absent beforehand. No app database/profile/key files are edited or cleared.
- After process termination outside JUnit cleanup, wrappers may remain. A subsequent failure is
  intentional evidence of leftover state; do not silently clear it. Parent decides isolation recovery.

## Parent-only run commands (not executed by this worker)

If the audit package/runner differ, use the actual installed package values; the package guard must match.

Before configuring credentials:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e securityAuditDisposable true \
  -e securityAuditTargetPackage dev.wissam.sshterm.audit \
  -e keystoreBiometricMode noEnrollment \
  -e class dev.wissam.sshterm.security.keystore.UnenrolledKeystoreRuntimeTest \
  dev.wissam.sshterm.audit.test/androidx.test.runner.AndroidJUnitRunner
```

After parent configures PIN + enrolls a fingerprint, unlock the emulator with that PIN and run:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e securityAuditDisposable true \
  -e securityAuditTargetPackage dev.wissam.sshterm.audit \
  -e keystoreBiometricMode enrolled \
  -e class dev.wissam.sshterm.security.keystore.ApprovedKeystoreRuntimeTest \
  dev.wissam.sshterm.audit.test/androidx.test.runner.AndroidJUnitRunner
```

For easier initial triage run one method at a time using `-e class fully.qualified.Class#method`.

For each visible **APPROVE** prompt, choose the enrolled fingerprint ID in the emulator's Extended
Controls → Fingerprint and press Touch Sensor. Parent may instead use an emulator console fingerprint
event for the ID actually enrolled. No such event is sent by test code. Do NOT send fingerprint events
in a timer/loop or pre-approve a sequence; wait for each new visible prompt. Never inject a touch for a
**CANCEL TEST** prompt: the test cancels it after one second, and unintended approval causes failure.

Logcat tag `SSHKEY_CRYPTO_RUNTIME` reports `PROMPT_START` and `PROMPT_RESULT`. A start log is not proof
that the dialog is visible; visually confirm the new prompt before touching. Each wait is bounded to
80 seconds. A missing/unmatched/late fingerprint produces a failure, not a fabricated pass. Preserve
instrumentation results plus prompt logs for acceptance evidence.

## Expected tests and operator input

| Test method | Real fingerprint approvals | Automatically cancelled prompts |
|---|---:|---:|
| productionStrongPromptDeniesWithoutEnrollment | 0 | 0 |
| unavailablePerOperationMigrationPreservesLegacyCiphertext | 0 | 0 |
| ecSignaturesRequireActualApprovalForEachOperation | 2 | 0 |
| rsaSignaturesRequireActualApprovalForEachOperation | 2 | 0 |
| actualCipherApprovalsMigrateDecryptAndFinalizeWithoutReusableGrant | 4 | 0 |
| cancellingActualMigrationPromptPreservesLegacyBytes | 0 | 1 |
| cancellingFinalizationRetainsVerifiedPrimaryAndLegacyRecovery | 2 | 1 |
| cancellingActualSignaturePromptDoesNotAuthorizePrivateOperation | 0 | 1 |

Approved run totals: 6 tests, 10 operation approvals, 3 automatic cancellations, plus the initial real app-unlock approval when cold-locked. JUnit method order must not
be assumed. No-enrollment run: 2 tests; no positive biometric runtime evidence is claimed from them.

## Assertions and limits

Signing tests use production KeyManager generation (EC-P256 / RSA-3072), inspect actual per-use policy,
invoke AuthorizedSignature with the real production biometric bridge, verify emitted signatures, and
reject a second call to the consumed signing object. Separate newly initialized JCA operations are
attempted before/after approvals; the rejection must specifically require user authentication, not just
be a generic algorithm/provider failure. The public key and non-exportability are checked.

V2 migration requires actual cipher authorization for encryption and independent decrypt verification.
The test compares legacy recovery bytes, reads V2 only through authorized decrypt, rejects fresh
unauthorized AES encrypt/decrypt operations, and requires another real approval before retiring recovery.
Cancellation tests assert a platform attempt was pending and retain exact legacy/recovery/primary bytes.
Unavailable authentication preserves legacy ciphertext and is not mistaken for successful migration.

A failed CryptoObject initialization, unsupported StrongBox fallback, unexpected Keystore error mapping,
wrong policy, fingerprint timeout, unsupported directory fsync or denied callback is a test FAILURE to
investigate; do not replace these operations with software keys or return true to make a test pass.

Worker validation: new test sources compiled directly with cached Kotlin 2.4, Android SDK/dependency
classes and a snapshot of parent's app/test classes. No instrumentation tests were run. Parent must
compile the complete test APK and execute both modes before claiming runtime acceptance.

## Integrated runtime evidence

Both modes passed on API 36 disposable emulators. The enrolled run used the actual app-unlock prompt first; source fixtures were corrected to avoid competing with that automatic prompt. Production KeyInfo inspection now recognizes duration 0 and legacy -1 as per-operation only when user authentication is required. See spec/audit-evidence/integrated for six enrolled and two no-enrollment results.
