package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopCardSurfaceTest {
    @Test
    fun `home entry cards are pure visuals with fixed layouts`() {
        // Cards render content only; tap and long-press-drag are handled by the ReorderableGrid
        // overlay, so the cards carry no clickable/hover handler of their own.
        listOf(
            Triple("QuickActionCard", "HOME_QUICK_ACTION_HEIGHT_DP", "padding(horizontal = 16.dp, vertical = 14.dp)"),
            Triple("FeatureEntryCard", "HOME_MODULE_CARD_HEIGHT_DP", "padding(horizontal = 14.dp, vertical = 12.dp)"),
        ).forEach { (name, height, contentPadding) ->
            val card = functionSource("AppHomePage.kt", name)
            assertFalse(card.contains("MutableInteractionSource"), name)
            assertFalse(card.contains("collectIsHoveredAsState"), name)
            assertFalse(card.contains(".clickable("), name)
            assertTrue(card.contains(".shadow(elevation = 1.dp"), name)
            assertTrue(card.contains("containerColor = colors.panel"), name)
            assertTrue(card.contains("borderColor = colors.border.copy(alpha = 0.72f)"), name)
            assertTrue(card.contains("contentPadding = 0.dp"), name)
            assertTrue(card.contains("verticalAlignment = Alignment.CenterVertically"), name)
            assertTrue(card.contains("maxLines = 2"), name)
            assertTrue(card.contains(contentPadding), name)
            // The card's own height is supplied by the caller (the grid), not baked in.
            assertFalse(card.contains(".height($height.dp)"), name)
        }
    }

    @Test
    fun `home entry cards share their outer surface without doubling padding`() {
        listOf("QuickActionCard", "FeatureEntryCard").forEach { name ->
            val card = functionSource("AppHomePage.kt", name)
            assertEquals(1, Regex("StudioCard\\(").findAll(card).count(), name)
            val surface = card.substringAfter("StudioCard(").substringBefore(") {")
            assertTrue(surface.contains("contentPadding = 0.dp"), name)
            assertTrue(card.contains("RoundedCornerShape(StudioTokens.cardRadius)"), name)
            assertTrue(card.contains("Modifier.fillMaxSize().padding("), name)
            assertFalse(card.contains(".clip(shape)"), name)
            assertFalse(card.contains(".border("), name)
        }
    }

    @Test
    fun `home omits the decorative workspace banner`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertFalse(source.contains("HomeHero("))
        assertFalse(source.contains("Brush.horizontalGradient("))
    }

    @Test
    fun `workspace card retains selection progress and independent action callbacks`() {
        val card = functionSource("SourceWorkspacesPage.kt", "WorkspaceCard")
        assertTrue(card.contains("onClick = onSelect"))
        assertTrue(
            card.contains(
                "if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer",
            ),
        )
        assertTrue(card.contains("LinearProgressIndicator(progress = { workspace.progress }"))
        assertTrue(card.contains("workspace.localizedMessage(language)"))
        listOf("onRefresh", "onToggleAiUpload", "onRemove").forEach { callback ->
            assertTrue(card.contains("onClick = $callback"), callback)
        }
        assertTrue(card.contains("contentPadding = StudioTokens.compactContentPadding"))
        assertTrue(card.contains("contentSpacing = 4.dp"))
    }

    @Test
    fun `workspace card uses a shared surface and retains material button semantics and text colors`() {
        val card = functionSource("SourceWorkspacesPage.kt", "WorkspaceCard")
        assertEquals(1, Regex("StudioCard\\(").findAll(card).count())
        assertTrue(card.contains(".clickable(role = Role.Button, onClick = onSelect)"))
        assertTrue(card.contains("contentPadding = StudioTokens.compactContentPadding"))
        assertFalse(card.contains("Column("))
        assertTrue(
            card.contains(
                "if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface",
            ),
        )
        assertEquals(2, Regex("color = contentColor").findAll(card).count())
        assertFalse(card.contains("CardDefaults"))
    }

    @Test
    fun `theme options retain their compact swatches selection colors and click target`() {
        val option = functionSource("DesktopAppSettingsPage.kt", "ThemeColorOption")
        assertTrue(option.contains("modifier.height(38.dp).clickable(onClick = onClick)"))
        assertTrue(
            option.contains(
                "if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant",
            ),
        )
        assertTrue(option.contains("if (selected) color.color else MaterialTheme.colorScheme.outline"))
        assertTrue(option.contains("Modifier.fillMaxSize().padding(horizontal = 10.dp)"))
        assertTrue(option.contains("Modifier.size(18.dp).background(color.color, CircleShape)"))
        assertTrue(option.contains("fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal"))
        assertTrue(option.contains("if (selected) {"))
    }

    @Test
    fun `theme option surfaces share the card primitive without changing label contrast`() {
        val option = functionSource("DesktopAppSettingsPage.kt", "ThemeColorOption")
        assertEquals(1, Regex("StudioCard\\(").findAll(option).count())
        assertTrue(option.contains("contentPadding = 0.dp"))
        assertTrue(option.contains("borderColor = if (selected) color.color else MaterialTheme.colorScheme.outline"))
        assertTrue(
            option.contains(
                "color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant",
            ),
        )
        assertFalse(option.contains("Surface("))
        assertFalse(option.contains("BorderStroke("))
    }

    private fun functionSource(
        file: String,
        name: String,
    ): String {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/$file"))
        val signature = "private fun $name("
        assertTrue(source.contains(signature), "missing $name in $file")
        return source.substringAfter(signature).substringBefore("\n@Composable")
    }
}
