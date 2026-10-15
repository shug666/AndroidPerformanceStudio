@file:Suppress("MagicNumber")

package com.androidperformancestudio.ui.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerPalettes
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.viewerColors
import com.androidperformancestudio.ui.viewerMaterialColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioWorkspaceStyleTest {
    @Test
    fun `shared dark surfaces match the original Startup workspace derivation`() {
        val shared = viewerColors(true)
        val original = ViewerPalettes.forDark(true, shared.accent)
        val base = original.canvasBackground.copy(
            red = original.canvasBackground.red * 0.45f,
            green = original.canvasBackground.green * 0.45f,
            blue = original.canvasBackground.blue * 0.45f,
        )
        val surface = original.accent.copy(alpha = 0.08f).compositeOver(base)

        assertEquals(surface, shared.panel)
        assertEquals(original.accent.copy(alpha = 0.035f).compositeOver(base), shared.workspace)
        assertEquals(original.accent.copy(alpha = 0.22f).compositeOver(surface), shared.border)
        assertEquals(original.accent.copy(alpha = 0.06f).compositeOver(surface), shared.sectionBackground)
    }

    @Test
    fun `shared light surfaces preserve the existing light palette`() {
        val shared = viewerColors(false)
        assertEquals(ViewerPalettes.forDark(false, shared.accent), shared)
    }

    @Test
    fun `Material containers and foregrounds follow shared workspace roles in both themes`() {
        listOf(false, true).forEach { dark ->
            val colors = viewerColors(dark)
            val scheme = viewerMaterialColorScheme(dark)
            assertEquals(colors.workspace, scheme.background)
            assertEquals(colors.panel, scheme.surface)
            assertEquals(colors.panel, scheme.surfaceContainer)
            assertEquals(colors.panel, scheme.surfaceContainerLow)
            assertEquals(colors.sectionBackground, scheme.surfaceVariant)
            assertEquals(colors.sectionBackground, scheme.surfaceContainerHigh)
            assertEquals(colors.border, scheme.outlineVariant)
            assertEquals(colors.primaryText, scheme.onSurface)
            assertEquals(colors.primaryText, scheme.onSecondaryContainer)
        }
    }

    @Test
    fun `nested viewer themes do not apply Startup surface tint twice`() =
        runDesktopComposeUiTest {
            var outer: ViewerColors? = null
            var inner: ViewerColors? = null
            var materialSurface = Color.Unspecified
            setContent {
                ViewerTheme(darkTheme = true) {
                    outer = LocalViewerColors.current
                    ViewerTheme(darkTheme = true) {
                        inner = LocalViewerColors.current
                        materialSurface = MaterialTheme.colorScheme.surface
                    }
                }
            }

            assertEquals(viewerColors(true), outer)
            assertEquals(outer, inner)
            assertEquals(outer?.panel, materialSurface)
        }
}
