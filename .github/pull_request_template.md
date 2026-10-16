## Change

Describe the problem, resulting behavior, and linked issue. For stacked PRs, name the base PR and required merge order.

## Validation

List exact commands and outcomes. Separate JVM tests, Android test compilation, emulator runs and physical-device checks. Include the tested commit. Do not treat a successful build or closed issue as production validation.

## Security and compatibility

Describe effects on host trust, private-key authorization/storage, clipboard, app lock and session lifecycle when applicable. State migrations and remaining risks. Preserve user app data: use disposable test packages for destructive testing; do not uninstall or clear the owner's app.

## Remaining work

Link unresolved acceptance criteria and follow-up issues. Keep the PR draft when required security/device checks have not passed.
