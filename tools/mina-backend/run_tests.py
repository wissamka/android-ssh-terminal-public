#!/usr/bin/env python3
"""Compile production transports and terminal bridge; run localhost JUnit without Gradle.

Uses cached dependencies only. Android API jars support compilation, not Android runtime tests.
The generated BuildConfig fixture mirrors the default SSHJ value; tests also select MINA directly.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--deps', type=Path, required=True, help='Pinned jars prepared by the PQ probe')
parser.add_argument('--java-home', type=Path, required=True, help='JDK 17+ home')
parser.add_argument('--android-jar', type=Path, required=True, help='Android platform android.jar')
args = parser.parse_args()
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

def jar(group, artifact, version='*'):
    choices = sorted(p for p in cache.glob(f'{group}/{artifact}/{version}/*/{artifact}-*.jar')
                     if 'sources' not in p.name and 'javadoc' not in p.name)
    if not choices:
        raise SystemExit(f'Missing cached artifact: {group}:{artifact}:{version}')
    return choices[-1]

compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'),
            jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0'),
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime'),
            jar('org.jetbrains.kotlin', 'kotlin-reflect'),
            jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
            jar('org.jetbrains', 'annotations')]
deps = [p.resolve() for p in args.deps.glob('*.jar') if 'sources' not in p.name]
deps += [jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0'),
         jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
         jar('javax.inject', 'javax.inject'), args.android_jar.resolve()]
java = str(args.java_home.resolve() / 'bin/java')
javac = str(args.java_home.resolve() / 'bin/javac')

def cp(items):
    return os.pathsep.join(map(str, items))

with tempfile.TemporaryDirectory(prefix='sshterm-mina-tests-') as scratch:
    output = Path(scratch) / 'classes'
    output.mkdir()
    config = Path(scratch) / 'BuildConfig.kt'
    config.write_text('package dev.wissam.sshterm\nobject BuildConfig { const val SSH_BACKEND = "sshj" }\n')
    subprocess.run([javac, '--release', '17', '-cp', cp([args.android_jar.resolve(),
                    jar('androidx.annotation', 'annotation-jvm'), jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0')]),
                    '-d', str(output), *map(str, (PROJECT / 'terminal-emulator/src/main/java').rglob('*.java'))], check=True)
    source_root = PROJECT / 'app/src/main/java/dev/wissam/sshterm/core'
    names = ['SshTypes', 'SshEngine', 'SshTransport', 'SshjTransportConnection', 'SshTerminalSession',
             'LifecycleResources', 'PortForwardManager', 'MinaBackend', 'MinaJumpTransport', 'MinaSigning',
             'SigningAuthorization', 'InteractiveChallenges', 'TofuKnownHostsVerifier', 'KnownHostsFileStore']
    sources = [source_root / 'ssh' / f'{name}.kt' for name in names]
    sources += [source_root / 'keys' / f'{name}.kt' for name in ['KeystoreKeyProvider', 'DeferredKeyProvider', 'KeyAuthenticationPolicy']]
    test_names = ['SshEngineProtocolTest', 'MinaBackendProtocolTest', 'MinaSecurityRegressionTest', 'SigningAuthorizationTest']
    sources += [PROJECT / 'app/src/test/java/dev/wissam/sshterm/core/ssh' / f'{name}.kt' for name in test_names]
    sources += list((PROJECT / "app/src/protocolTest/java").rglob("*.kt"))
    sources.append(config)
    subprocess.run([java, '-cp', cp(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', cp([output, *deps]),
                    '-d', str(output), *map(str, sources)], check=True)
    subprocess.run([java, '-cp', cp([output, *deps]), 'org.junit.runner.JUnitCore',
                    *['dev.wissam.sshterm.core.ssh.' + name for name in test_names]], check=True, timeout=180)
