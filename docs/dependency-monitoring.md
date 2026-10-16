# Dependency monitoring

The dependency-monitoring workflow resolves the Gradle dependency graph on main
pushes, every Monday, or manual runs on main, and submits direct and transitive
versions to GitHub. It does not build, sign or publish the application. Resolution
or submission failures fail the job.

Dependabot configuration checks Gradle and action updates weekly and groups aligned
BouncyCastle updates. Actions are pinned to verified public commit IDs. The
write-capable submission job runs only on main, never on pull-request code; checkout
does not persist repository credentials.

After integration, the repository owner must verify that Dependency graph and
Dependabot alerts are enabled in GitHub's code-security settings. Confirm that the
first successful snapshot includes all resolved BC artifacts (prov, pkix, util),
sshj and their transitive dependencies. The hosted Android SDK must support the
project's compileSdk for Gradle configuration; a failed first run remains a gate
until fixed. No hosted workflow or graph submission was executed in the worker.

A submitted graph enables advisory monitoring; it is not a clean vulnerability
scan or proof that every advisory is reachable from SSH. Existing findings still
need package/version and call-path review. Crypto roundtrip, debug/minified release
and device authentication tests remain independent release gates.

Sources:
- [Pinned Gradle dependency-submission documentation](https://github.com/gradle/actions/blob/0723195856401067f7a2779048b490ace7a47d7c/docs/dependency-submission.md)
- [GitHub Dependabot alerts](https://docs.github.com/en/code-security/dependabot/dependabot-alerts/about-dependabot-alerts)
