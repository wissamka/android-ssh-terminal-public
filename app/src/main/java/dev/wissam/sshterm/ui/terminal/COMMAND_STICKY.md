# Command-field sticky keys (#32)

An armed/locked Ctrl or Alt applies immediately to the next identifiable command-field key,
leaving the entire buffered command (including its selection) unchanged. ARMED is consumed on
transmission; LOCKED persists. No command history is recorded for these keystrokes.

Physical keys are handled in preview before Compose editing. Their corresponding up/repeat events
are consumed locally, so they do not also reach the field's edit callback. Unicode decoding removes
Ctrl/Alt meta bits but preserves Shift. Special keys use the existing terminal key encoder.
Without modifiers, Up/Down still navigate suggestions and Enter/IME Send first commits a selected
suggestion for editing. With sticky modifiers, IME Enter follows the same immediate special-key path.
The explicit Send button sends the buffered command unchanged plus CR; it does not transform its
first character or consume a modifier intended for a future keystroke.

For soft input, only an exact single-code-point insertion is a keystroke. Single ASCII preedit
insertions are immediate as well, because many IMEs mark even the initial 'c' as composing. The
rejected edit restores the accepted TextFieldValue and cancels that composition. Non-ASCII preedit
waits for commit; partial surrogate input, multi-code-point insertions, replacements and deletions
are not transformed into terminal control streams. Multi-character paste edits the buffer and
leaves modifiers armed/locked. A one-code-point paste is indistinguishable from a key insertion
through this field API and follows the key rule. Enter does not submit unfinished IME composition.
Existing native TerminalView input paths and symbol-strip writes are unchanged.

Verification: `python3 /private/tmp/compile-sticky.py` compiled changed production sources plus
AndroidCommandInputRuntimeTest and SecurityRuntimeFixture using cached Kotlin/Compose dependencies;
CommandInputPolicyTest: 11 tests passed. `git diff --check` passed.

Four runtime tests use the production activity/VM and the existing disposable-audit-only security
fixture: Up/Down plus physical Enter, IME suggestion commit without Send, soft ARMED consumption,
and native LOCKED key buffer preservation followed by Unicode paste. They were authored and
compilation-checked, not executed here. They assert production field/VM state, not transport byte
counts or remote signal delivery. The local fallback shell has no PTY; it cannot prove Ctrl+C
interrupts a remote foreground process.

Parent validation gaps: serialized Gradle/packaging, execute the runtime class once the shared
fixture is ready, real keyboard IME preedit/commit/reset roundtrips (including duplicate suppression),
and Ctrl+C against a separately authorized PTY-backed transport. No Gradle, adb, package install,
network/VPS call, push, transport change, or key-authentication change was performed here.
