@file:Suppress("FunctionName")

package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.androidperformancestudio.ui.LocalViewerColors

/** Compact, repeatable card surface for dense profiler workspaces. */
@Composable
public fun StudioCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    borderColor: Color? = null,
    contentColor: Color = LocalContentColor.current,
    contentPadding: Dp = StudioTokens.compactContentPadding,
    contentSpacing: Dp = StudioTokens.panelGap,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(StudioTokens.cardRadius)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier =
                modifier
                    .clip(shape)
                    .background(containerColor ?: colors.panel)
                    .border(
                        width = StudioTokens.borderWidth,
                        color = borderColor ?: colors.border,
                        shape = shape,
                    ).padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
