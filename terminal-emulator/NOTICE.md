# NOTICE

This module is vendored from the Termux project
(https://github.com/termux/termux-app, subdirectory `terminal-emulator/`,
commit 401bbe54b8f4e68302b1ff70678015a24628fb1d), which releases the
`terminal-emulator` and `terminal-view` libraries under the Apache
License 2.0 as an explicit exception to the GPLv3 license of the rest of
the termux-app repository (see that repository's LICENSE.md). Those
libraries in turn derive from Terminal Emulator for Android
(https://github.com/jackpal/Android-Terminal-Emulator), Apache-2.0.

Local modifications (marked with in-file notices where applicable):
- The native PTY layer (src/main/jni/, JNI.java) was removed.
- TerminalSession was refactored into an abstract, transport-agnostic class.
- TerminalSessionClient.setTerminalShellPid() was removed.
- Build files were rewritten (Gradle Kotlin DSL).

- Remote OSC 52 clipboard requests use a separate, default-deny callback with explicit app consent.
- Per-emulator color palettes isolate session themes.
