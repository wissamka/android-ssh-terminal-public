# Database migration and key recovery evidence

Implementation review: 2026-09-12. Android execution remains pending the coordinated
parent build and instrumentation run. These tests are release gates, not evidence
that device recovery has already passed.

## Fix date and deployment gate

Destructive fallback existed before this fix. Any earlier statement that the app
had no destructive migration was incorrect: historical AppModule called
fallbackToDestructiveMigration(dropAllTables = true), including for the missing
v1 upgrade path.

The removal was implemented on **2026-09-12 (America/Los_Angeles)** in worker
commit 83576b7 and independently in parent commit 6393259. The parent removal is
confirmed in its current source; preserve it when integrating the worker's shared
AppDatabase.open factory. Neither change proves that an installed owner's data
survives an upgrade.

**The deployment candidate remains blocked until the preservation instrumentation
tests pass.** Standalone compilation and SQLite smoke checks do not satisfy that
gate. The execution entries below must remain pending until actual Android results
are recorded.

## Historical schema provenance

The historical AppDatabase and Entities sources were inspected directly in git:

| Version | Source commit | Schema change |
| --- | --- | --- |
| 1 | 20b5550 | keys, profiles, command_history |
| 2 | 3fc6b03 | forwarding_rules with profile foreign key and index |
| 3 | e417700 | snippets |
| 4 | a7d52f2 | identities; nullable profiles.identityId and index |

v1→v2 does not change any key/profile columns. Constructor defaults in these Kotlin
entities are not SQL DEFAULT clauses. Historical exports were disabled, so
HistoricalDatabaseFixture independently transcribes the entity schema and records
these sources. It does not invoke the migration under test or invent Room hashes.

The production factory registers 1→2→3→4 with no destructive fallback. Unsupported
versions and invalid schemas fail open attempts; they must never trigger an
automatic database reset. All historical upgrade tests open the production factory,
which runs Room's generated schema validation, then close and reopen the database.

## Coverage and remaining validation

| Scenario | Instrumentation coverage | Execution |
| --- | --- | --- |
| v1→v4, v2→v4, v3→v4 | MigrationTest: keys, storage references for both protection tiers, profiles, jump links, history, forwarding/snippets counts, foreign-key integrity, reopen | Pending |
| Interrupted migration | Transaction rollback of data and DDL; retry with real migrations succeeds | Pending |
| Unknown future version | Production open fails; version, key references and profiles survive | Pending |
| Invalid source schema | Validation fails; original version/key data survive and partial identities DDL rolls back | Pending |
| Same-device software key after v1 upgrade | KeyRecoveryTest: real AES-GCM store, recovered DAO row, decryption and signature verification | Pending |
| Encrypted Ed25519 export/import | Real KeyManager export/import; public key equality; wrong passphrase leaves source usable | Pending |
| Missing encrypted file | Loading fails; key row/profile link remain for diagnosis | Pending |
| Hardware signature after upgrade | Metadata/reference preservation only | Device auth/signature exercise still required |
| Keystore invalidation, device loss, uninstall or transfer | Not established by these tests | Explicit recovery procedure/device tests still required |

A preserved key row is not proof that private material is usable. Software files
depend on the same device's Android Keystore wrapping key; copying database and
encrypted files alone does not establish cross-device recovery. Hardware private
keys are non-exportable. Encrypted Ed25519 export/import covers the currently
supported portable software-key path, not hardware recovery.

## Parent integration gates

1. Run KSP/Android compilation and commit the real schema export under app/schemas.
2. Run MigrationTest and KeyRecoveryTest on the coordinated test device. Also run
   the existing crypto/routing tests after combining the signing worker's changes.
3. Verify minified release and the hardware-auth/signature scenarios separately.
4. Preserve original database/key files when diagnosing a failed upgrade. Do not
   propose clearing app storage or reinstalling as a recovery step.

Standalone compiler validation is available at
/private/tmp/sshterm-migration-compile.py in the worker environment. It uses cached
Android/Room dependencies and a snapshot of parent's compiled classes; it does not
run KSP, Room validation, Gradle, an emulator, or hardware crypto.
