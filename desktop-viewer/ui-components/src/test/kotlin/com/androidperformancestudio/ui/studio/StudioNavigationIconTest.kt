package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_home
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_source_workspaces
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_layout_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_cpu_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_trace_analyzer
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_memory_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_frame_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_startup_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_battery
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_network
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_gpu_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_benchmark
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_method_recording
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_collapse
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_expand
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the shell navigation vectors through the production painter path so a malformed SVG or a
 * missing resource fails here instead of showing a blank rail icon at runtime.
 */
@OptIn(ExperimentalTestApi::class)
class StudioNavigationIconTest {
    private val navigationIcons: List<Pair<String, DrawableResource>> =
        listOf(
            "home" to Res.drawable.ic_nav_home,
            "source_workspaces" to Res.drawable.ic_nav_source_workspaces,
            "layout_inspector" to Res.drawable.ic_nav_layout_inspector,
            "cpu_profiler" to Res.drawable.ic_nav_cpu_profiler,
            "trace_analyzer" to Res.drawable.ic_nav_trace_analyzer,
            "memory_profiler" to Res.drawable.ic_nav_memory_profiler,
            "frame_profiler" to Res.drawable.ic_nav_frame_profiler,
            "startup_profiler" to Res.drawable.ic_nav_startup_profiler,
            "battery" to Res.drawable.ic_nav_battery,
            "network" to Res.drawable.ic_nav_network,
            "gpu_inspector" to Res.drawable.ic_nav_gpu_inspector,
            "benchmark" to Res.drawable.ic_nav_benchmark,
            "method_recording" to Res.drawable.ic_nav_method_recording,
            "rail_collapse" to Res.drawable.ic_nav_rail_collapse,
            "rail_expand" to Res.drawable.ic_nav_rail_expand,
        )

    @Test
    fun `every navigation vector decodes and paints visible pixels`() =
        runDesktopComposeUiTest(width = 640, height = 120) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    Row {
                        navigationIcons.forEach { (name, resource) ->
                            Icon(
                                painter = painterResource(resource),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp).testTag("nav-icon-$name"),
                                tint = Color.Red,
                            )
                        }
                    }
                }
            }

            navigationIcons.forEach { (name, _) ->
                val pixels = onNodeWithTag("nav-icon-$name").captureToImage().toPixelMap()
                var tinted = 0
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        val pixel = pixels[x, y]
                        if (pixel.red > 0.5f && pixel.green < 0.3f && pixel.blue < 0.3f) {
                            tinted++
                        }
                    }
                }
                assertTrue(tinted > 0, "navigation icon '$name' painted no vector pixels")
            }
        }
}
