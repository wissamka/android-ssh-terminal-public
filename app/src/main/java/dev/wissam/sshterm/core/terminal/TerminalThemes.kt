package dev.wissam.sshterm.core.terminal

import com.termux.terminal.TerminalColors
import java.util.Properties

/** Per-session terminal palettes. Profile "default" inherits the app choice. */
object TerminalThemes {

    data class Theme(
        val id: String,
        val label: String,
        val foreground: String,
        val background: String,
        val cursor: String,
        /** color0..color15 as #rrggbb. */
        val ansi: List<String>,
    )

    val all: List<Theme> = listOf(
        Theme(
            id = "default", label = "SSH Term Dark",
            foreground = "#e6edf3", background = "#0d1117", cursor = "#3fb950",
            ansi = listOf(
                "#484f58", "#ff7b72", "#3fb950", "#d29922", "#58a6ff", "#bc8cff", "#39c5cf", "#b1bac4",
                "#6e7681", "#ffa198", "#56d364", "#e3b341", "#79c0ff", "#d2a8ff", "#56d4dd", "#f0f6fc",
            ),
        ),
        Theme(
            id = "solarized_dark", label = "Solarized Dark",
            foreground = "#839496", background = "#002b36", cursor = "#93a1a1",
            ansi = listOf(
                "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
                "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3",
            ),
        ),
        Theme(
            id = "gruvbox_dark", label = "Gruvbox Dark",
            foreground = "#ebdbb2", background = "#282828", cursor = "#ebdbb2",
            ansi = listOf(
                "#282828", "#cc241d", "#98971a", "#d79921", "#458588", "#b16286", "#689d6a", "#a89984",
                "#928374", "#fb4934", "#b8bb26", "#fabd2f", "#83a598", "#d3869b", "#8ec07c", "#ebdbb2",
            ),
        ),
        Theme(
            id = "light", label = "Light",
            foreground = "#24292f", background = "#ffffff", cursor = "#0969da",
            ansi = listOf(
                "#24292f", "#cf222e", "#116329", "#4d2d00", "#0969da", "#8250df", "#1b7c83", "#6e7781",
                "#57606a", "#a40e26", "#1a7f37", "#633c01", "#218bff", "#a475f9", "#3192aa", "#8c959f",
            ),
        ),
    )

    /** Explicit dark override, since the legacy profile value "default" means inheritance. */
    const val DARK_OVERRIDE = "ssh_term_dark"
    val profileChoices: List<Theme> get() = listOf(all.first().copy(id = DARK_OVERRIDE)) + all.drop(1)

    fun byId(id: String): Theme = all.firstOrNull { it.id == id } ?: all.first()

    /** Unknown/missing profile choices also inherit; an unknown app choice falls back to dark. */
    fun resolve(profileChoice: String?, appChoice: String): Theme = when {
        profileChoice == DARK_OVERRIDE -> all.first()
        profileChoice == null || profileChoice == "default" -> byId(appChoice)
        else -> all.firstOrNull { it.id == profileChoice } ?: byId(appChoice)
    }

    private val schemes by lazy {
        all.associate { theme ->
            val props = Properties().apply {
                setProperty("foreground", theme.foreground)
                setProperty("background", theme.background)
                setProperty("cursor", theme.cursor)
                theme.ansi.forEachIndexed { i, color -> setProperty("color$i", color) }
            }
            theme.id to com.termux.terminal.TerminalColorScheme().apply { updateWith(props) }
        }
    }

    /** Legacy activity-startup seed for not-yet-created emulators; live sessions own snapshots. */
    fun applyGlobal(id: String) {
        val palette = schemes.getValue(byId(id).id).mDefaultColors
        System.arraycopy(palette, 0, TerminalColors.COLOR_SCHEME.mDefaultColors, 0, palette.size)
    }

    /** No shared emulator defaults are mutated. Reapplying an unchanged theme preserves OSC colors. */
    fun apply(colors: TerminalColors, id: String): Boolean = colors.setColorScheme(schemes.getValue(byId(id).id))

    fun backgroundColor(colors: TerminalColors): Int =
        colors.mCurrentColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND]

    fun backgroundColor(id: String): Int =
        schemes.getValue(byId(id).id).mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND]
}
