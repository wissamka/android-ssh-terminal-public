# Billing/export race fixes

Based on parent `d16832f`, cloned with `--no-local` into `/private/tmp/sshterm-worker-review-fixes`. This change owns billing receipt persistence and private-key export handoff only.

## Export

Each export flow owns a one-shot ExportHandoff. Activity pause/stop/destroy invalidates it immediately, cancels the owned operation and dismisses the flow. Disposal also invalidates/cancels, but only once: an old flow cannot cancel a successor after completion. Re-entering the foreground does not revive a paused attempt; the user must explicitly begin a fresh export.

Immediately before creating a file/URI, the callback checks the current MainActivity lifecycle (RESUMED, not finishing/destroyed), its live app-lock state, and attempt ownership. The check, encrypted file creation and chooser handoff are synchronous on the UI thread, so lifecycle changes cannot interleave between the check and handoff. Late/duplicate results cannot share or dismiss a successor flow. The existing unique, confined FileProvider URI behavior is preserved.

## Billing

ReceiptPersistenceGuard tracks confirmed purchase-token ownership and a revision that changes only when that ownership changes. Query start, query failure and invalid receipt responses do not invalidate a pending acknowledgement. A successful acknowledgement can therefore cache its verified receipt even if a later refresh failed. Repeated successful queries for the same token also preserve it.

A confirmed empty/pending-only result or different account token invalidates earlier tickets. Returning to the old token after an intervening revocation creates a new revision, so an old acknowledgement still cannot resurrect access. The existing receipt signature verification, owner/release rules, prices and no-backend behavior are unchanged.

## Validation

- 26 focused JVM tests passed via Kotlin/JUnit directly, including 10 new regressions. Export tests exercise actual temporary-file creation as the protected side effect and verify zero files/shares on denied handoffs. Billing tests cover durable write/read after a failed refresh, repeated ownership, revocation, account switch and token restoration after revocation.
- All current app Kotlin sources compiled using Kotlin/Compose 2.4.0, actual Billing 9.1.0, cached dependencies and parent terminal/R jars. This used a temporary BuildConfig stub, not Gradle or generated-code validation.
- `git diff --check` passed. No Gradle, adb, VPS action or push.

Remaining parent validation: normal build/code generation; Android delayed export completion after Home/Instant lock; cancellation during biometric prompt; valid foreground encrypted export; actual Play callback ordering and offline restart. JVM tests establish guard behavior and filesystem effects, not Android chooser/biometric behavior or a live Play purchase.
