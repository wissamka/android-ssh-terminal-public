# Background terminal lifecycle — issue #31

Android 14+ uses `specialUse`, its permission, and the manifest subtype explanation for
user-initiated interactive SSH terminals and their tunnels. Earlier releases use the two-argument
`startForeground`. Interactive terminal work is not a finite data synchronization job or device
text-message continuity. This category selection is an implementation judgment, not Google approval.
Android documents that special-use declarations are reviewed in Play Console; any distribution
submission still needs its own review. No package is distributed by this change.

Sources checked 2026-09-12:
- https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- https://developer.android.com/develop/background-work/services/fgs/service-types#remote-messaging
- https://developer.android.com/develop/background-work/services/fgs/service-types#short-service

Both timeout overloads remove foreground status and call `stopSelf` before invalidating sessions.
SSH close seals resource ownership immediately, cancels connection prompts, interrupts the connector,
and closes sockets/forwards off the main thread. A late successful connect is closed instead of
published. Ordinary reconnect teardown does not seal the session or cancel its interaction lease.
The service is nonsticky; a cold start with no live sessions stops immediately. A foreground service
raises process priority but does not guarantee survival. Finished tabs do not keep it running.

An atomic file under `noBackupFilesDir` records only UUID session tokens and optional positive saved
profile IDs. No hostnames, usernames, terminal output, passwords, key references, or key bytes are
serialized. On process recreation, records become ended-session cards; reconnect explicitly follows
the saved-profile credential flow and starts a new shell. Unsaved sessions require a new connection.
A missing/deleted profile has a dismissible card. Closing a tab removes its journal entry. Abrupt
process death before a close is committed conservatively leaves an ended card; it never opens a socket.

## Parent integration

- Requires the parent-owned `ResolvedConnection.cancelPrompts: () -> Unit = {}` API from #30.
  Definitive termination invokes it once per resolved hop; shared leases must tolerate repeated
  cancellation across hops. Transient reconnect does not cancel it.
- The manager now injects `ProfileRepository` and touches recency only after the first successful
  shell setup (CONNECTED). Remove the existing early `touchLastConnected(profile.id)` from the
  connection flow in `TerminalViewModel` when integrating #35/#53. This branch only adds recovery
  properties to that view model; it does not rewrite the parent's prompt flow.

## Verification

Standalone cached Kotlin 2.4 compiler / Compose plugin, Android SDK 37 stubs, cached dependencies and
parent build classes compiled the changed service, session, manager, recovery, UI and test sources.
The compilation used a temporary copy of SshTypes with the agreed parent cancelPrompts API and the
branch's Entities source to avoid the parent's concurrently compiled enum additions.
Command used: `python3 /private/tmp/compile-background-ui.py` (local verification harness only).
JUnitCore `dev.wissam.sshterm.core.ssh.BackgroundLifecycleTest`: **8 tests passed**.
`git diff --check`: passed.

Instrumented tests are authored and compilation-checked for the installed service declaration and
AtomicFile repository recreation. They deliberately do not launch services or connect to SSH.
No Gradle, adb, VPS call, package install/distribution, or push was run. Parent must run the serialized
Android build/test checks, Hilt/manifest packaging validation, and device service start/stop/timeout
and process-kill scenarios before claiming end-to-end Android validation.
