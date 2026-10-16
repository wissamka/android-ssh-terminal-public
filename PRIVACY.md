# Privacy Policy — SSH Term

_Implementation draft updated 2026-09-12. The publisher must supply a contact, host the final policy,
and reconcile Play Data Safety with the exact distributed build before publishing._

SSH Term has no developer account, advertising, analytics, or automatic crash reporting to the developer.
Connection profiles, private key files, history, snippets and trusted host keys are stored locally.
Google Play processes Pro purchases when billing is configured. This app therefore does not promise
that it contacts only SSH servers or that no third party processes any information.

## Local information

- Profiles contain server addresses, usernames, key references and preferences. Profile/database data
  is protected by Android's app sandbox; the entire database is not separately encrypted by this app.
- Android Keystore keys are non-exportable through the app. The UI reports the inspected security level;
  Keystore storage alone does not guarantee secure hardware. Software/imported keys are encrypted at rest.
  Legacy wrapping protection and migration limitations are documented in the security implementation notes.
- SSH password and interactive challenge answers are not saved in the profile database. Passwords can
  remain in memory during a session for reconnect. Private material must be available transiently for
  software signing; no promise of perfectly zeroed JVM/Compose memory is made.
- Command history is optional and scoped to its saved profile by default. Global fallback is opt-in.
  History can contain secrets entered as command arguments; Settings can disable recording or clear it.
- Local crash logs can contain exception details. They are not uploaded automatically. Nonsecret recovery
  records identify ended sessions for reconnection; they do not retain passwords or private keys.
- A verified purchase receipt/token and its signature may be stored privately for offline Pro access.
  These records are excluded from Android backup.

## Network and sharing

SSH traffic, authentication and terminal commands go to the servers you select. Port forwarding can
carry traffic to destinations you configure. SSH authentication sends a signature, not your private key.
Configured Google Play billing queries product information and ownership and handles checkout through
Google Play under Google's applicable terms and privacy policy. The app does not receive card details,
and no purchase token is sent to an application-owned server by this implementation.

Copy/share/export actions deliberately send the chosen content to Android's clipboard or the application
you select. Supported private-key export is passphrase-encrypted. Other applications' retention policies
apply after sharing. Server-requested clipboard writes require a separate explicit Copy action.
There is no built-in SFTP browser, cloud synchronization, advertising or automatic developer upload.

## Device access and deletion

Network access enables SSH and configured purchases. A foreground notification supports active sessions;
Android may still stop them. Biometric authentication is handled by Android. Optional battery-optimization
exemption is requested in the session context. Secure-window flags protect terminal and key screens.

The app can delete individual keys, profiles and history. Clearing app storage or uninstalling removes local
app data and can permanently destroy Keystore keys. Save a supported encrypted software-key export before
uninstalling if you need portability; non-exportable keys cannot be recovered that way. Uninstalling does
not remove public keys previously installed on servers or cancel/refund a Google Play purchase.

## Contact and Play disclosure checklist

Publisher contact: **not yet configured**. Do not publish this placeholder.

The former blanket “no data collected/shared” form answers are superseded. Review Google Play Billing's
current SDK disclosures, financial/purchase history treatment and user-directed server/export exemptions
against the actual app and SDK versions. Record the submitted form and hosted policy in release evidence.
See [billing behavior and official sources](docs/BILLING.md), [audit](VALIDATION.md),
and [release implementation](VALIDATION.md). No form has been submitted by this task.
