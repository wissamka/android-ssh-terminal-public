# Profile terminal themes (#52 / #35)

`ProfileEntity.colorScheme = "default"` means **Use app theme**. It inherits the current
Settings theme for every session opened from that profile. An explicit profile choice overrides
Settings. The picker stores `ssh_term_dark` for an explicit SSH Term Dark override, so a dark
profile can stay dark while the app default is Light. Missing/deleted profiles, quick-connect,
local shells, and unknown profile theme IDs inherit the app choice. An unknown app ID falls back
to SSH Term Dark. Existing database values need no migration.

The view model combines the app setting, saved profile choices and live sessions, resolving by
each session's profile ID. Editing a profile or the app setting updates the affected live palettes;
new/lazily initialized emulators receive their palette through the view's onEmulatorSet callback.

Each TerminalColors owns a defensive copy of its reset defaults. Theme application changes that
instance's foreground, background, cursor and ANSI 0–15 colors, preserving the standard extended
color cube. OSC color edits and resets cannot change another emulator. Reapplying the same theme
preserves that session's OSC edits. The legacy activity-startup applyGlobal entry point only seeds
construction defaults; existing emulators never read those shared defaults again.

The terminal View background comes from its attached emulator, including OSC background changes.
Theme-only redraws invalidate the view, without resetting terminal contents, selection, search
highlights or scroll position. Session attachment retains the existing deferred refresh.

Verification: cached javac/Kotlin/Compose compilation of changed Java, Kotlin, UI and test sources;
JUnitCore TerminalThemesTest: 6 tests passed. Local command:
`python3 /private/tmp/compile-themes-ui.py`. `git diff --check` passed.
No Gradle, adb, network targets or pushes. Parent still owns the serialized Android build and
on-device visual checks for simultaneous themes, profile edits, selection and search.
