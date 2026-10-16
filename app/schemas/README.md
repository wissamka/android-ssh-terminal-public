# Room schema exports

The production database enables Room schema export. The existing KSP
`room.schemaLocation` setting writes generated JSON here during the parent build.

Commit the generated `dev.wissam.sshterm.data.db.AppDatabase/4.json` after that build.
No generated schema JSON or identity hash has been fabricated for this change.
Historical releases disabled export; their checked-in entity source is the provenance
for the independent instrumentation fixtures (see `docs/database-recovery.md`).

If historical JSON is needed for MigrationTestHelper later, generate it by compiling
each historical revision with schema export enabled in an isolated checkout; do not
copy the current identity hash into an older schema.
