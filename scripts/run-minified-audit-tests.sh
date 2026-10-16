#!/usr/bin/env bash
set -euo pipefail
# Build/check only, or install exclusively on an explicitly named disposable emulator.
mode=${1:?Usage: scripts/run-minified-audit-tests.sh --build-only|emulator-SERIAL [sshj|mina-pq]}
backend=${2:-sshj}
[[ "$backend" == sshj || "$backend" == mina-pq ]] || { echo 'Backend must be sshj or mina-pq.' >&2; exit 1; }
[[ "$mode" == --build-only || "$mode" =~ ^emulator-[0-9]+$ ]] || { echo 'Use --build-only or an explicit emulator serial.' >&2; exit 1; }
[[ $# -le 2 ]] || { echo 'Unexpected arguments.' >&2; exit 1; }
task_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$task_sdk" ]] || { echo 'Set ANDROID_HOME to the Android SDK.' >&2; exit 1; }
cd "$(dirname "$0")/.."
# Build tasks only: no connected/install tasks and no release signing request.
./gradlew -PauditMinified=true -PownerPro=false "-PsshBackend=$backend" \
  :app:assembleAuditRelease --console=plain

# Check the app artifact before installation, including its R8 mapping and signing identity.
evidence=$(mktemp -d "${TMPDIR:-/tmp}/sshterm-minified-smoke.XXXXXX")
echo "Evidence directory: $evidence"
python3 - "$task_sdk" "$backend" "$evidence" <<'PY'
import json
import pathlib
import re
import subprocess
import sys

sdk = pathlib.Path(sys.argv[1])
backend = sys.argv[2]
versions = [p for p in (sdk / 'build-tools').iterdir() if re.fullmatch(r'\d+(\.\d+)+', p.name)]
if not versions:
    raise SystemExit('No stable Android build-tools installation found.')
tools = max(versions, key=lambda p: tuple(map(int, p.name.split('.'))))
aapt = tools / 'aapt2'
signer = tools / 'apksigner'
app = pathlib.Path('app/build/outputs/apk/auditRelease/app-auditRelease.apk')
evidence = pathlib.Path(sys.argv[3])

def require(ok, message):
    if not ok:
        raise SystemExit(message)

def output(*args):
    return subprocess.check_output(list(map(str, args)), text=True)

for apk, package in [(app, 'dev.wissam.sshterm.audit')]:
    metadata = json.loads((apk.parent / 'output-metadata.json').read_text())
    require(metadata['applicationId'] == package, f'Unexpected package in {apk.parent}')
    require(len(metadata['elements']) == 1 and metadata['elements'][0]['outputFile'] == apk.name,
            f'Unexpected/split artifact in {apk.parent}')
    badging = output(aapt, 'dump', 'badging', apk)
    require(f"package: name='{package}'" in badging, f'Actual APK package mismatch: {apk}')
    if apk == app:
        require('application-debuggable' not in badging, 'Audit app unexpectedly debuggable.')

launchers = re.findall(r"^launchable-activity: name='([^']+)'", badging, re.MULTILINE)
require(len(launchers) == 1, 'Missing or ambiguous launcher activity in audit APK.')
require(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.$]*', launchers[0]), 'Invalid launcher class.')
(evidence / 'component.txt').write_text(f'dev.wissam.sshterm.audit/{launchers[0]}')

cert = output(signer, 'verify', '--print-certs', app)
require(re.search(r'Signer #1 certificate DN:.*CN=Android Debug', cert),
        f'{app} is not signed with an Android debug certificate.')
fingerprints = re.findall(r'Signer #\d+ certificate SHA-256 digest: (\S+)', cert)
require(len(fingerprints) == 1, f'Expected one verified signer for {app}')
(evidence / 'certificate.txt').write_text(cert)

configs = list(pathlib.Path('app/build/generated/source/buildConfig/auditRelease').rglob('BuildConfig.java'))
require(len(configs) == 1, 'Missing or ambiguous auditRelease BuildConfig.')
config = configs[0].read_text()
for field in ['DEBUG', 'OWNER_PRO']:
    require(re.search(rf'boolean\s+{field}\s*=\s*false\s*;', config), f'{field} must be false.')
require(f'SSH_BACKEND = "{backend}";' in config, 'Unexpected compiled SSH backend.')
mapping = pathlib.Path('app/build/outputs/mapping/auditRelease/mapping.txt')
require(mapping.is_file() and mapping.stat().st_size > 0, f'Missing R8 mapping: {mapping}')
print('Verified audit app package, release flags, debug signing and app R8 mapping.')
PY

[[ "$mode" != --build-only ]] || exit 0
# Every adb invocation is scoped to the explicitly requested emulator.
python3 - "$task_sdk/platform-tools/adb" "$mode" "$evidence" <<'PY'
import pathlib
import re
import subprocess
import sys
import time

adb, serial = sys.argv[1:3]
evidence = pathlib.Path(sys.argv[3])
package = 'dev.wissam.sshterm.audit'
component = (evidence / 'component.txt').read_text()

def run(*args, check=True):
    result = subprocess.run([adb, '-s', serial, *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    if check and result.returncode:
        raise RuntimeError(f'{args}: {result.stdout}')
    return result

def pids():
    result = run('shell', 'pidof', package, check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(f'Cannot inspect audit process: {result.stdout}')
    value = result.stdout.strip()
    if value and not re.fullmatch(r'\d+(?:\s+\d+)*', value):
        raise RuntimeError(f'Unexpected pidof response: {value}')
    return set(value.split())

# Reject phones before any install, force-stop, or launch.
if not re.fullmatch(r'emulator-\d+', serial):
    raise SystemExit('An explicit emulator-NUMBER serial is required.')
if run('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
    raise SystemExit('Target is not an emulator.')
install = run('install', '-r', 'app/build/outputs/apk/auditRelease/app-auditRelease.apk')
(evidence / 'install.txt').write_text(install.stdout)
if not re.search(r'^Success\s*$', install.stdout, re.MULTILINE):
    raise SystemExit('Audit app installation did not report Success.')
run('shell', 'am', 'force-stop', package)
if pids():
    raise SystemExit('Audit app is still running after force-stop.')
# Device time delimits crash evidence without clearing any device logs.
since = run('shell', 'date', '+%m-%d_%H:%M:%S.000').stdout.strip().replace('_', ' ')
if not re.fullmatch(r'\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000', since):
    raise SystemExit(f'Unexpected device timestamp: {since}')
failure = None
try:
    launch = run('shell', 'am', 'start', '-W', '-n', component,
                 '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER',
                 check=False)
    (evidence / 'launch.txt').write_text(launch.stdout)
    print(launch.stdout, end='')
    if launch.returncode or not re.search(r'^Status: ok\s*$', launch.stdout, re.MULTILINE):
        raise RuntimeError('Cold launch did not report Status: ok.')
    initial = pids()
    if len(initial) != 1:
        raise RuntimeError(f'Expected one audit app process after launch, got {initial}.')
    with (evidence / 'process.txt').open('w') as report:
        report.write(f'Launch PID: {sorted(initial)}\n')
        for second in range(1, 11):
            time.sleep(1)
            current = pids()
            report.write(f'{second}s: {sorted(current)}\n')
            report.flush()
            if current != initial:
                raise RuntimeError('Audit process exited or restarted during the 10-second smoke window.')
except (RuntimeError, subprocess.SubprocessError) as error:
    failure = str(error)
finally:
    # adb logcat forwards its argument vector; literal shell quotes corrupt the timestamp.
    crash = run('logcat', '-b', 'crash', '-d', '-v', 'brief', '-T', since)
    (evidence / 'crash.txt').write_text(crash.stdout)
    target = re.escape(package) + r'(?::[\w.]+)?'
    if re.search(r'(?:Process:\s*' + target + r'(?=,|\s|$)|>>>\s*' + target + r'\s*<<<)',
                 crash.stdout):
        failure = 'Fatal crash buffer contains an audit-package crash in the smoke window.'
if failure:
    raise SystemExit(f'{failure} Evidence: {evidence}')
print(f'Cold-launch smoke passed: Status: ok, same PID survived 10 seconds, no audit-package fatal report. Evidence: {evidence}')
print('This smoke does not validate biometrics, SSH, crypto, entitlements or full functionality.')
PY
