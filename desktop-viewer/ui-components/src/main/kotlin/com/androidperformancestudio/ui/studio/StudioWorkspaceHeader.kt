@file:Suppress("FunctionName", "LongParameterList", "MagicNumber")

package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography

/** Shared compact workspace heading, derived from Startup Profiler's visual baseline. */
@Composable
public fun StudioWorkspaceHeader(
    title: String,
    subtitle: String? = null,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(StudioTokens.heroCardRadius)
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = StudioTokens.workspaceHeaderMinHeight)
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(colors.panel, colors.accent.copy(alpha = 0.22f))))
                .border(StudioTokens.borderWidth, colors.border, shape),
    ) {
        val showSupportingText = maxWidth >= StudioTokens.workspaceHeaderSupportingMinWidth
        WorkspaceHeaderGraphic(Modifier.matchParentSize())
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            WorkspaceHeaderLabels(title, subtitle, titleModifier, Modifier.weight(1f))
            if (showSupportingText && supportingText != null) {
                Text(
                    text = supportingText,
                    modifier = Modifier.width(170.dp),
                    color = colors.secondaryText,
                    style = ViewerTypography.workspaceSubtitle,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceHeaderLabels(
    title: String,
    subtitle: String?,
    titleModifier: Modifier,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
        // Keep fallback-font measurement and accessibility layout on the same text path.
        Text(
            text = AnnotatedString(title),
            modifier = titleModifier.fillMaxWidth().semantics { heading() },
            color = colors.primaryText,
            style = ViewerTypography.workspaceTitle,
            fontWeight = FontWeight.Bold,
        )
        subtitle?.let {
            Text(
                text = AnnotatedString(it),
                modifier = Modifier.fillMaxWidth(),
                color = colors.secondaryText,
                style = ViewerTypography.workspaceSubtitle,
            )
        }
    }
}

@Composable
private fun WorkspaceHeaderGraphic(modifier: Modifier) {
    val accent = LocalViewerColors.current.accent
    Canvas(modifier) {
        val startX = (size.width - 240.dp.toPx()).coerceAtLeast(0f)
        val graphicWidth = size.width - startX
        val mountain =
            Path().apply {
                moveTo(startX, size.height)
                lineTo(startX + graphicWidth * 0.40f, size.height * 0.42f)
                lineTo(startX + graphicWidth * 0.55f, size.height * 0.70f)
                lineTo(startX + graphicWidth * 0.78f, size.height * 0.16f)
                lineTo(size.width, size.height * 0.63f)
                lineTo(size.width, size.height)
                close()
            }
        drawPath(mountain, accent.copy(alpha = 0.20f))
    }
}
