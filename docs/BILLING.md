# Free / Pro billing — issue #43

Implementation date: 2026-09-12. Scope: configurable local Free/Pro entitlements; live Play configuration is not supplied.
Business plan: `business-plan/Android SSH Terminal — Business Plan.md` in the parent Terminal workspace.

## Configuration and scope

Uses Google Play Billing **9.1.0**, verified against Google's current release notes and actual Maven AAR. A single INAPP non-consumable permanent buy option is supported. No consume call, subscription, rental, preorder, external checkout, fake purchase, release override, backend, service credential or analytics is introduced. The explicitly authorized owner development capability is described below.

Both Gradle properties default to empty in all variants:

- `playProProductId`: approved Play Console one-time product ID.
- `playBillingPublicKey`: Play Console application licensing RSA public key, Base64 DER without line breaks. This is a public verification key, not a service credential.

Missing/invalid configuration disables checkout and restore safely. Neither a price nor a real product ID is invented. Configure one permanent buy option without promotional offers; multiple offers deliberately disable checkout until offer-selection behavior is designed. Displayed localized price and checkout offer token come from the same ProductDetails response. Details are refreshed on foreground/retry and never persisted.

Free 3 profiles / 1 active session is a **proposal**, represented centrally in EntitlementPolicy. `FreeTierLimits` accepts explicit alternative limits without granting paid status; update user-facing limit copy if changing these defaults. Pro permits unlimited counts. No snippet limit or unimplemented workflow extra is promised. Security, input, tunneling, keys/export, existing profile access and existing sessions are not gated.

## Verification, offline, refunds, account changes

A PURCHASED receipt must match configured product and installed package, contain a token, and pass SHA1withRSA signature verification using the Play public key. Pending transactions never grant Pro. Acknowledgement happens after verification/grant; errors prompt retry, and subsequent ownership queries retry acknowledgement. Nothing is consumed. Cancellation leaves entitlement unchanged. Already-owned responses trigger restore.

Only after successful acknowledgement (or an already acknowledged queried purchase) is the original signed receipt cached atomically in Android's no-backup private directory. Every process start re-verifies it; malformed/tampered data grants nothing. The cache does not contain a writable `isPro` preference. Cache-write failure keeps current access and reports that offline restoration was not saved.

**Freshness/grace policy:** re-query at every activity resume and explicit Restore/Retry. A verified cached non-consumable has no wall-clock expiration during an outage (indefinite offline grace); unknown/new installations remain Free. This avoids billing outages disrupting legitimate offline work. The UI distinguishes cached access from successfully queried access. No billing path can terminate a session or erase/lock profile/key data.

A successful complete ownership query without the product clears cached Pro, including an account switch or a refund/revocation that Play has reflected in its ownership response. A pending-only query also removes Pro and shows pending. Failed queries and invalid matching receipts preserve prior access and show an error. An older acknowledgement cannot overwrite a subsequent ownership result. Queries are serialized/coalesced; callbacks are marshalled to the main thread.

**Client-only limitation:** signed receipts establish authenticity, not current server-side ownership. Google recommends server-side verification and voided-purchase processing. This implementation cannot promptly detect refunds while offline, prevent rooted-client patching/replay of previously signed receipts, or promise immediate revocation for all refund types. Refund without revoking ownership may continue to appear owned. Cached Play ownership may lag; account selection belongs to Play, with no app-level Google identity collected. No new backend is authorized or added. Acceptance of this fraud/offline tradeoff is a paid-launch decision. If server verification is chosen later, document hosting cost, token processing/retention, and privacy issue #16 before implementation.

## Authoritative enforcement and launch safety default

The follow-up merged parent through `7a2d85d` (including `04c8f78` and background recovery) before edits. ProfileRepository now uses one ProfileCreationGate mutex around authoritative DAO count + insert. Every new profile uses that path; existing-ID update, retrieval, use, deletion and exports bypass the gate. Above-cap data is never removed or hidden. Simultaneous saves cannot exceed the cap; failed inserts do not consume a slot. UI save failures keep the editor open and show the upgrade/recovery message.

SshSessionManager owns one SessionAdmissionGate. Both SSH and local shells reserve before construction and registration, hence before terminal sizing can start transport. Connecting and live sessions share the Free capacity. Registration/construction failure, terminal completion, close and service destruction release reservations. In-place reconnect retains its existing session/reservation. Billing events never release reservations or stop sessions. Main-thread manager use remains required by TerminalSession. Normal/quick connect already catch denial and display the error; local-shell opening now catches it too. Messages direct the user to Settings → Free & Pro.

Credential prompts currently occur before manager reservation. They do not start SSH; the authoritative manager still denies excess concurrent attempts before transport creation. Reserving before prompts is a future UX improvement, not an enforcement bypass. Signing/authentication prompt ownership was left intact.

**Authorized launch safety default:** enforce proposed 3 profiles / 1 session only when a syntactically valid product ID and usable Play RSA public key are configured in the build. Missing/invalid configuration leaves current audit/beta use unrestricted without granting Pro. A later product lookup/network outage does not turn restrictions off; verified offline Pro remains usable. Existing over-limit profiles and sessions stay available after configuring billing. This is migration safety, not a paid grandfather grant. The same FreeTierLimits object drives enforcement and screen/error copy; changing packaging remains a product decision. No snippet/security/export cap is added.

## Explicit owner development artifact

the maintainer explicitly authorized owner Pro for his development phone artifact in the follow-up. `-PownerPro=true` sets `BuildConfig.OWNER_PRO` **only in debug**. Default and release both hardcode false; release never reads the property for that field. Runtime also requires `BuildConfig.DEBUG`, through DevelopmentAccess.ownerPro. The existing parent `-PauditInstall=true` package selection is untouched: owner debug normally uses `.debug`, owner audit debug uses `.audit`.

Owner mode labels the purchase screen **Owner development Pro**, bypasses creation caps, and disables checkout/restore/Play refresh. It does not set Entitlement.pro, fake a purchase/signature, load the offline receipt, write a cached receipt, or grant anything to release. Existing real receipts are left untouched. This is a separate local development capability; no price is hardcoded.

Parent validation before phone installation: build debug with `-PauditInstall=true -PownerPro=true`, then normal debug with `-PownerPro=true`; inspect generated BuildConfig for OWNER_PRO=true and correct application IDs. Generate release with ownerPro=true too and verify OWNER_PRO=false and DEBUG=false. Worker inspected the build-script branches and tested the runtime truth table, but did **not** run Gradle or claim generated BuildConfig evidence. Release remains gated by all paid-launch requirements below.

## Validation and paid-launch blockers

Passed 16 JVM JUnit tests with Kotlin 2.4.0 directly, without Gradle: Free boundaries, concurrent authoritative profile creation, unconfigured-build bypass without paid status, failed insert recovery, shared local/SSH capacity, debug-only owner truth table without paid state, over-limit data access, unlimited Pro, restore/pending, offline failures, successful-empty-query revocation, reservation release/reconnect, downgrade preservation, simultaneous-session race, signed-receipt tampering and absent/malformed public keys.

All app Kotlin sources (including repository, session manager and UI enforcement) compile directly with Kotlin 2.4.0 and Compose compiler, JVM 17, Android API 37 jar, official Billing 9.1.0 and cached AndroidX/Hilt dependencies. This standalone check uses a development BuildConfig stub and previously built terminal/R jars; it is **not** a Gradle dependency-resolution, Hilt generation, generated BuildConfig, APK, shrinker or device validation. `git diff --check` passes. No Gradle, adb, VPS action or push performed.

Parent must run coordinated debug/minified builds, generated BuildConfig checks and the normal test suite after cherry-picking the enforcement commit. Play Console/publisher account, approved product/public key/price, licensed-tester environment and physical validation are unavailable to this worker. Real checkout is unvalidated. Test purchase + acknowledgement; cancel; pending-complete/pending-cancel; process death/restart; offline before/after verified ownership; reinstall restore; account switch; refund with revoke; acknowledgement failure and retry; missing/unsupported Play Store; unavailable/multiple products; background/foreground during checkout; and locale price consistency. API callbacks/retries, cache I/O and these end-to-end transitions still need integration/device coverage. Billing permission comes from the library manifest; verify merged manifest and privacy/store disclosures (#16).

## Official sources checked

- Release notes: https://developer.android.com/google/play/billing/release-notes (9.1.0, 2026-06-18).
- Integration/acknowledgement/pending/foreground queries: https://developer.android.com/google/play/billing/integrate.
- Verification and client-only limitations: https://developer.android.com/google/play/billing/security.
- Artifact: https://dl.google.com/dl/android/maven2/com/android/billingclient/billing/9.1.0/billing-9.1.0.aar.
