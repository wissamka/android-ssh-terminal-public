# Optimized runtime validation

Run `scripts/run-minified-audit-tests.sh emulator-SERIAL sshj` or select `mina-pq`.
The runner requires an explicit disposable emulator and installs only the isolated audit package.
It validates the package, release flags, debug test signing and R8 mapping, then cold-launches the app and checks process survival for ten seconds and fatal crash reports.

This is a launch smoke test. It does not validate all optimized features. The separate debug instrumentation harness cannot simply be applied to an R8-optimized application because R8 changes the APIs it references. Production code is not broadly exempted from optimization to fabricate a pass.

See [current validation](../VALIDATION.md) and the [production checklist](../PRODUCTION.md).
