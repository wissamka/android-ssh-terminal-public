package dev.wissam.sshterm.ui.terminal

enum class StickyState { OFF, ARMED, LOCKED }
enum class CommandInputAction { PASS, PREVIOUS, NEXT, COMMIT, SEND }

/** Only the focused command field routes keyboard events through this policy. */
fun commandInputAction(key: String, focused: Boolean, visible: Boolean, selected: Int, count: Int): CommandInputAction {
    if (!focused) return CommandInputAction.PASS
    return when (key) {
        "Up" -> CommandInputAction.PREVIOUS
        "Down" -> CommandInputAction.NEXT
        "Enter" -> if (visible && selected in 0 until count) CommandInputAction.COMMIT else CommandInputAction.SEND
        else -> CommandInputAction.PASS
    }
}

fun nextSuggestionIndex(selected: Int, delta: Int, count: Int): Int = when {
    count <= 0 -> -1
    selected !in 0 until count -> if (delta < 0) count - 1 else 0
    else -> (selected + delta).mod(count)
}

/** Matches TerminalView's Ctrl mapping and ESC-prefixed Alt, consuming ARMED per code point. */
fun modifiedTerminalText(text: String, ctrl: StickyState, alt: StickyState): String = buildString {
    var offset = 0
    while (offset < text.length) {
        var cp = text.codePointAt(offset)
        val first = offset == 0
        offset += Character.charCount(cp)
        if (alt == StickyState.LOCKED || (first && alt == StickyState.ARMED)) append('\u001b')
        if (ctrl == StickyState.LOCKED || (first && ctrl == StickyState.ARMED)) {
            cp = when (cp) {
                in 97..122 -> cp - 96
                in 65..90 -> cp - 64
                32, 50 -> 0
                91, 51 -> 27
                92, 52 -> 28
                93, 53 -> 29
                94, 54 -> 30
                95, 55, 47 -> 31
                56 -> 127
                else -> cp
            }
        }
        appendCodePoint(cp)
    }
}

/** Only an unambiguous single-code-point insertion is a soft keystroke. Replacements, deletions,
 * ambiguous composition and multi-character paste remain edits and do not consume modifiers.
 * A single ASCII preedit keystroke is immediate too (soft keyboards compose even Ctrl+C). */
fun stickyCommandInsertion(
    before: String,
    after: String,
    ctrl: StickyState,
    alt: StickyState,
    composing: Boolean = false,
): String? {
    if (ctrl == StickyState.OFF && alt == StickyState.OFF) return null
    var prefix = 0
    while (prefix < before.length && prefix < after.length) {
        val old = before.codePointAt(prefix)
        if (old != after.codePointAt(prefix)) break
        prefix += Character.charCount(old)
    }
    if (prefix >= after.length) return null
    val cp = after.codePointAt(prefix)
    if (composing && cp !in 0x20..0x7e) return null
    if (cp in 0xD800..0xDFFF) return null // Incomplete UTF-16 from an IME is not a key.
    val end = prefix + Character.charCount(cp)
    if (after.substring(end) != before.substring(prefix)) return null
    // Newlines/tabs from paste or an IME edit are not executable command-field keys.
    if (Character.isISOControl(cp)) return null
    return modifiedTerminalText(String(Character.toChars(cp)), ctrl, alt)
}

/** Native dead-key markers and surrogate halves must remain in the IME's composition path. */
fun modifiedCommandCodePoint(codePoint: Int, ctrl: StickyState, alt: StickyState): String? {
    if (ctrl == StickyState.OFF && alt == StickyState.OFF) return null
    if (!Character.isValidCodePoint(codePoint) || codePoint <= 0 || codePoint in 0xD800..0xDFFF) return null
    return modifiedTerminalText(String(Character.toChars(codePoint)), ctrl, alt)
}
