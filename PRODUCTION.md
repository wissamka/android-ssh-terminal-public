# Production acceptance checklist

Source publication is not a paid APK release. See [validation](VALIDATION.md) for completed software checks.

Before a production release, complete:

- Full optimized feature testing, beyond build and launch smoke checks.
- Physical Android hardware-backed signing and biometric/enrollment recovery testing.
- Bluetooth keyboard, real IME composition and accessibility checks.
- OEM background/network-handoff endurance and broader server interoperability.
- Play product configuration and real purchase, restore, pending, refund and account tests.
- Store listing, privacy hosting, permissions declarations, release signing and independent security review.

Do not test destructively on an installation holding real keys. The provided runners require explicit disposable emulators and isolated audit packages.
