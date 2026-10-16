# Issue #28 software wrapper follow-up — 2026-09-12

Storage-core follow-up to a311f50. No UI/VM/AppLock/metadata changes; no wiki update.

## Concrete parent API

```kotlin
val authorizer = KeyFileAuthorizer { request ->
    // Through the parent's InteractionLease + prompt queue:
    // BiometricPrompt.CryptoObject(request.cipher), BIOMETRIC_STRONG only.
    // Return true only for the matching success callback; do not consume or replace the cipher.
    // Cancel/dismiss on timeout, lease close, background policy, error, or caller cancellation.
    leaseBackedCipherPrompt(request.storageId, request.purpose, request.cipher)
}
```

`KeyFileAuthorizationRequest` has `storageId`, `purpose`, and the exact initialized `Cipher`.
Purposes are `ENCRYPT_MIGRATION`, `VERIFY_MIGRATION`, `DECRYPT`. Each invocation requires a fresh
operation-specific prompt. The suspend boundary has a cooperative 90-second timeout; callback
implementations must suspend cancellably, not block or swallow cancellation.

KeyManager entry points (file-store equivalents also public):

- `hasAuthBoundEnvelope(entity)`: version marker only, not proof of valid/available key policy.
- `hasLegacyRecovery(entity)`: whether retained legacy recovery still prevents full at-rest binding.
- `migrateSoftwareKeyAuthorized(entity, authorizer)`: two CryptoObject prompts (encrypt, verification
  decrypt), then durable promotion. Returns recovery File; primary now V2, recovery retained.
- `retireSoftwareKeyRecoveryAuthorized(entity, authorizer)`: another fresh decrypt verifies the primary,
  syncs its directory and retires legacy recovery. Only successful completion closes this migration step.
- `keyProviderForAuthorized(entity, authorizer, passphrase)`: authorized V2 decrypt, then existing parser.
- `exportKeyAuthorized(entity, exportPassphrase, authorizer, storedPassphrase)`: authorized V2 decrypt,
  existing Ed25519 encrypted export. Existing export eligibility/passphrase constraints preserved.

Wire V2 load/export **before enabling migration**. Existing synchronous load/export intentionally throw
`KeyFileAuthorizationRequiredException` on V2 instead of bypassing authorization. New software
generation/import keep writing the legacy format pending an explicitly authorized first-use migration;
no generation/import UI breaks before migration is enabled. Existing hardware paths are unchanged.
A cancelled verification leaves the legacy primary intact. A crash/exception during promotion can leave
V2 + recovery: on next use recognize V2 and offer finalization, not another legacy migration. Do not
silently restore a legacy backup on V2 authentication/tag/key-invalidation failure. Recovery needs an
explicit user decision and is not automated by this commit. Do not expose raw storage filenames as a
human key name; parent can capture the key label when constructing the authorizer.

Key deletion now deletes both primary and retained recovery (and reports failure). No Keystore alias
is removed. Interrupted temp files contain ciphertext only and are not automatically decrypted/reused.

## Envelope and replacement guarantees

V2 header is `STKF`, version 2, IV length 12, 12-byte IV, GCM ciphertext/tag. Header plus stable storage
filename is GCM AAD, so moving an envelope to another key filename fails authentication. Unknown
versions, truncation, excessive size, corruption and wrong identities fail closed. Shared V2 alias is
`sshterm-key-file-auth-v2`; original `sshterm-key-file-master` and all existing key aliases stay intact.
New V2 alias requires per-operation strong biometrics on API 30+, duration -1 on API 26–29. Existing V2
alias policy is checked for required per-operation auth. Missing V2 alias is not regenerated when V2
material exists in the key directory; decrypt never creates it. No weak/device-credential fallback is
provided for V2 CryptoObject operations. Availability/enrollment/invalidation failures preserve files.

Replacement sequence:

1. Read legacy snapshot; decrypt using unchanged legacy master.
2. Fresh cipher authorization → produce V2 candidate.
3. Independent fresh decrypt authorization → compare recovered plaintext; wipe temporary byte arrays.
4. Under commit lock, reject a changed/deleted primary; write old ciphertext to unique temporary file,
   fsync it, atomically install `.legacy-recovery`, fsync directory, verify backup bytes, fsync directory.
5. Write V2 to unique temporary file, fsync, atomic replacement of primary, fsync directory, verify bytes.
6. Retain recovery until a separate fresh authorized decrypt of the primary succeeds and the directory
   sync succeeds. Delete recovery, then sync directory. A final sync failure can mean cleanup is pending
   or the backup may reappear after power loss; do not claim finalized success on an exception.

No path deletes old ciphertext before a verified candidate and durable recovery exist. No delete-and-
retry rename fallback. A preexisting conflicting backup aborts instead of overwriting it. If power loss
occurs before promotion durability, the durable recovery remains; after promotion it is independently
recoverable by V2. Physical power-loss behavior, OEM filesystem quirks and Android directory fsync are
not device-validated. JVM injected failures establish control-flow preservation, not a power-loss proof.
The commit lock/migration mutex protects this singleton in one process; concurrent external writers or
multiple app processes are outside this implementation's contract.

## Actual signing lifecycle self-review

SSHJ's reader calls `Signature.sign()`; `runBlocking` there is an independent coroutine root and does
not inherit the connecting coroutine's Job. The parent's InteractionLease must cancel the reader's
outstanding prompt request on close. A cancellable authorizer and the 90-second timeout prevent an
indefinite suspended prompt, but cannot preempt a callback that blocks or ignores cancellation.

Fixed: reader interruption cancels the runBlocking prompt and now restores the interrupted flag; no
private signature follows. Added cancellation checks after callback success and before signing.
Tests exercise a suspended reader interruption and a separately cancelled lease Job, both denying
without delegate signing. This does not make prompt success plus JCA sign atomic with lifecycle close:
a close racing after completed authorization cannot revoke a signature already being computed. Parent
must suppress use/transmission after close and discard that connection; no background authorization
may be cached or reused. Callback success is not granted to another attempt.

## Validation and still-open acceptance

Direct cached Kotlin/JUnit run: **13 tests pass** (8 signing tests, 5 V2/transaction tests). Tests cover
fresh grants, denial, exception, signature one-shot use, host verification, algorithm factory paths,
JCA operation identity, reader interrupt, lease cancellation, GCM tamper/identity/version rejection,
legacy format classification, retained recovery/finalization, directory-sync failure injection and
concurrent/conflicting file protection. Full changed keys core including KeyManager plus SSH boundary
compiled directly against cached Android/SSHJ/Room/Hilt/BC dependencies. No Gradle, adb, VPS or push.

**Do not close #28 from this commit:**

- UI/lease wiring, actual Android strong-biometric CryptoObject execution, API/OEM compatibility,
  enrollment/invalidation behavior, full app/minified build and physical storage-fault tests are pending.
- Existing/new unmigrated software files remain legacy non-auth-bound; migrated files with recovery
  retained also still have a non-auth-bound copy. UI must distinguish pending/finalized states.
- V2 binds decryption to a biometric operation. It does not make Ed25519/imported private keys
  non-exportable or bind their JCA signature to secure hardware. Parsed software providers and immutable
  parser Strings retain plaintext material; zeroization is best effort for our byte arrays only.
- Existing signing authorizer still gates every actual software signature, including reuse/reconnect;
  authorized provider loading is not a signing grant. A stronger per-signature decrypt/no-provider-cache
  redesign is not implemented by this storage-only commit. Arbitrary in-process access to a decrypted
  private key is outside the app gate's protection.
- Legacy hardware window policy cannot be changed in place and remains as previously reported.
- Automatic/manual legacy recovery restoration UI and verified protection-label updates remain parent
  integration work. No silent fallback, migration, recovery deletion or issue closure is enabled in UI.
