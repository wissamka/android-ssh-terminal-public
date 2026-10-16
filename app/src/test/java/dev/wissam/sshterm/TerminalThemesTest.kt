package dev.wissam.sshterm

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TextStyle
import dev.wissam.sshterm.core.terminal.TerminalThemes
import org.junit.Assert.*
import org.junit.Test

class TerminalThemesTest {
    @Test fun defaultAndMissingProfileInheritAppChoice() {
        for (choice in listOf(null, "default", "deleted-theme")) {
            assertEquals("light", TerminalThemes.resolve(choice, "light").id)
        }
        assertEquals("default", TerminalThemes.resolve("default", "unknown").id)
    }

    @Test fun explicitProfilesOverrideAppIncludingExplicitDark() {
        assertEquals("solarized_dark", TerminalThemes.resolve("solarized_dark", "light").id)
        assertEquals("default", TerminalThemes.resolve(TerminalThemes.DARK_OVERRIDE, "light").id)
        assertEquals(TerminalThemes.all.size, TerminalThemes.profileChoices.size)
    }

    @Test fun simultaneousPalettesAndOscResetAreIndependent() {
        val sharedBefore = TerminalColors.COLOR_SCHEME.mDefaultColors.clone()
        val light = TerminalColors()
        val dark = TerminalColors()
        TerminalThemes.apply(light, "light")
        TerminalThemes.apply(dark, "gruvbox_dark")
        assertEquals(0xffffffff.toInt(), TerminalThemes.backgroundColor(light))
        assertEquals(0xff282828.toInt(), TerminalThemes.backgroundColor(dark))
        light.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, "#123456")
        light.tryParseColor(1, "#123456")
        light.reset(TextStyle.COLOR_INDEX_BACKGROUND)
        assertEquals(0xffffffff.toInt(), TerminalThemes.backgroundColor(light))
        assertEquals(0xff282828.toInt(), TerminalThemes.backgroundColor(dark))
        light.reset()
        assertEquals(0xffcf222e.toInt(), light.mCurrentColors[1])
        assertEquals(0xffcc241d.toInt(), dark.mCurrentColors[1])
        assertArrayEquals(sharedBefore, TerminalColors.COLOR_SCHEME.mDefaultColors)
    }

    @Test fun appChangeOnlyChangesInheritedPalette() {
        val inherited = TerminalColors()
        val explicit = TerminalColors()
        TerminalThemes.apply(inherited, TerminalThemes.resolve("default", "light").id)
        TerminalThemes.apply(explicit, TerminalThemes.resolve("solarized_dark", "light").id)
        explicit.tryParseColor(2, "#123456")
        TerminalThemes.apply(inherited, TerminalThemes.resolve("default", "gruvbox_dark").id)
        assertFalse(TerminalThemes.apply(explicit, TerminalThemes.resolve("solarized_dark", "gruvbox_dark").id))
        assertEquals(0xff282828.toInt(), TerminalThemes.backgroundColor(inherited))
        assertEquals(0xff002b36.toInt(), TerminalThemes.backgroundColor(explicit))
        assertEquals(0xff123456.toInt(), explicit.mCurrentColors[2])
    }

    @Test fun activityStartupSeedCannotChangeLiveSessionResetDefaults() {
        val saved = TerminalColors.COLOR_SCHEME.mDefaultColors.clone()
        try {
            val colors = TerminalColors()
            TerminalThemes.apply(colors, "solarized_dark")
            TerminalThemes.applyGlobal("light")
            colors.reset()
            assertEquals(0xff002b36.toInt(), TerminalThemes.backgroundColor(colors))
            assertEquals(0xffffffff.toInt(), TerminalThemes.backgroundColor(TerminalColors()))
        } finally {
            System.arraycopy(saved, 0, TerminalColors.COLOR_SCHEME.mDefaultColors, 0, saved.size)
        }
    }

    @Test fun paletteDefaultsAreDefensivelyCopiedAndCubePreserved() {
        val source = TerminalColorScheme()
        val colors = TerminalColors()
        source.mDefaultColors[1] = 0xff123456.toInt()
        colors.setColorScheme(source)
        source.mDefaultColors[1] = 0xffabcdef.toInt()
        colors.reset(1)
        assertEquals(0xff123456.toInt(), colors.mCurrentColors[1])
        val baseline = TerminalColorScheme().mDefaultColors
        TerminalThemes.apply(colors, "light")
        for (index in 16..255) assertEquals(baseline[index], colors.mCurrentColors[index])
        assertEquals(0xff24292f.toInt(), colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(0xff0969da.toInt(), colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR])
    }
}
