package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MacOsWindowChromeTest {
    @Test
    fun `only macOS embeds the title bar into the app background`() {
        assertTrue(MacOsWindowChrome.embedsTitleBar("Mac OS X"))
        assertTrue(MacOsWindowChrome.embedsTitleBar("macOS 15.5"))
        assertFalse(MacOsWindowChrome.embedsTitleBar("Windows 11"))
        assertFalse(MacOsWindowChrome.embedsTitleBar("Linux"))
    }

    @Test
    fun `the window makes the native title bar transparent and lets content cover it`() {
        val main = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/Main.kt"))

        assertTrue(main.contains("SideEffect { MacOsWindowChrome.install(window) }"))
        // A full-size content view only reads the client properties under these exact keys.
        assertTrue(MacOsWindowChrome.FULL_WINDOW_CONTENT == "apple.awt.fullWindowContent")
        assertTrue(MacOsWindowChrome.TRANSPARENT_TITLE_BAR == "apple.awt.transparentTitleBar")
        assertTrue(MacOsWindowChrome.WINDOW_TITLE_VISIBLE == "apple.awt.windowTitleVisible")
    }

    @Test
    fun `the native title text is hidden because the app draws its own`() {
        val chrome =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/MacOsWindowChrome.kt"),
            )

        assertTrue(chrome.contains("putClientProperty(WINDOW_TITLE_VISIBLE, false)"))
        assertTrue(chrome.contains("MacOsWindowChrome.titleBarDividerHeight"))
    }

    @Test
    fun `the strip uses the theme background with inverted text and a bottom divider`() {
        val page =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )

        assertTrue(page.contains("MacOsTitleBar("))
        assertTrue(page.contains("title = windowTitle?.value ?: APP_DISPLAY_NAME"))
        assertTrue(page.contains("background = windowColors.toolbar"))
        assertTrue(page.contains("contentColor = windowColors.primaryText"))
        assertTrue(page.contains("dividerColor = windowColors.border"))
        assertTrue(page.contains("viewerColors("))
        // Outside ViewerTheme so the fixed point height is not inflated by the display scale.
        assertTrue(page.indexOf("MacOsTitleBar(") < page.indexOf("ViewerTheme("))
    }
}
