#!/usr/bin/env python3
"""Standalone javac/JUnit runner; no Gradle, Android SDK, agent or user SSH files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

BASE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--deps', type=Path, required=True, help='Directory for pinned Maven jars')
parser.add_argument('--java-home', type=Path, help='JDK 17+ home (otherwise JAVA_HOME/PATH)')
parser.add_argument('--fetch', action='store_true', help='Allow missing public jars to be fetched from Maven Central')
parser.add_argument('mode', choices=['test', 'probe'])
parser.add_argument('probe_args', nargs=argparse.REMAINDER)
args = parser.parse_args()
args.deps.mkdir(parents=True, exist_ok=True)
jars = []
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
for dep in json.loads((BASE / 'dependencies.lock.json').read_text()):
    group, artifact, version = dep['group'], dep['artifact'], dep['version']
    name = f'{artifact}-{version}.jar'
    jar = args.deps / name
    if not jar.exists():
        cached = next(cache.glob(f'{group}/{artifact}/{version}/*/{name}'), None)
        if cached:
            shutil.copyfile(cached, jar)
        elif args.fetch:
            url = f'https://repo.maven.apache.org/maven2/{group.replace(".", "/")}/{artifact}/{version}/{name}'
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != dep['sha256']:
                raise SystemExit(f'Dependency hash mismatch: {name}')
            jar.write_bytes(data)
        else:
            raise SystemExit(f'Missing {name}; supply the jar or explicitly use --fetch')
    if hashlib.sha256(jar.read_bytes()).hexdigest() != dep['sha256']:
        raise SystemExit(f'Dependency hash mismatch: {name}')
    jars.append(str(jar.resolve()))
java_home = args.java_home or (Path(os.environ['JAVA_HOME']) if 'JAVA_HOME' in os.environ else None)
def tool(name):
    return str(java_home / 'bin' / name) if java_home else name
cp = os.pathsep.join(jars)
with tempfile.TemporaryDirectory(prefix='sshterm-pq-classes-') as output:
    sources = list((BASE / 'src/main/java').rglob('*.java'))
    if args.mode == 'test': sources += list((BASE / 'src/test/java').rglob('*.java'))
    subprocess.run([tool('javac'), '--release', '17', '-cp', cp, '-d', output, *map(str, sources)], check=True)
    target = ['org.junit.runner.JUnitCore', 'dev.wissam.sshterm.pq.HybridProbeTest'] if args.mode == 'test' else ['dev.wissam.sshterm.pq.LocalProbe', *args.probe_args]
    subprocess.run([tool('java'), '-cp', output + os.pathsep + cp, *target], check=True, timeout=120)
