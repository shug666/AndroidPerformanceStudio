@file:Suppress("MatchingDeclarationName")

package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.analysis.AnalyzedFrame
import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import com.androidperformancestudio.frame.analysis.FrameDeadlineVerdict
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.JankType
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.all_frames
import com.androidperformancestudio.frame.presentation.generated.resources.budget
import com.androidperformancestudio.frame.presentation.generated.resources.deadline_miss_signal
import com.androidperformancestudio.frame.presentation.generated.resources.deadline_misses
import com.androidperformancestudio.frame.presentation.generated.resources.duration
import com.androidperformancestudio.frame.presentation.generated.resources.frame_source_frame_metrics
import com.androidperformancestudio.frame.presentation.generated.resources.frame_source_gfxinfo
import com.androidperformancestudio.frame.presentation.generated.resources.frame_source_jank_stats
import com.androidperformancestudio.frame.presentation.generated.resources.frame_source_perfetto
import com.androidperformancestudio.frame.presentation.generated.resources.jank_only
import com.androidperformancestudio.frame.presentation.generated.resources.jank_stats
import com.androidperformancestudio.frame.presentation.generated.resources.jank_stats_reasons
import com.androidperformancestudio.frame.presentation.generated.resources.jank_types
import com.androidperformancestudio.frame.presentation.generated.resources.missed_vsync
import com.androidperformancestudio.frame.presentation.generated.resources.over_budget_by
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal enum class FrameViewFilter {
    ALL,
    JANK_ONLY,
    DEADLINE_MISSES,
}

internal fun FrameAnalysisResult.filtered(
    source: FrameSource?,
    filter: FrameViewFilter,
): FrameAnalysisResult {
    if (source == null && filter == FrameViewFilter.ALL) return this

    val visibleSamples =
        frames
            .filter { frame ->
                (source == null || frame.sample.source == source) && frame.matches(filter)
            }.map(AnalyzedFrame::sample)
    val filtered = FrameJankAnalyzer().analyze(visibleSamples)
    val visibleFrameIds = filtered.frames.mapTo(mutableSetOf()) { it.sample.frameId }
    return filtered.copy(
        clusters =
            clusters.mapNotNull { cluster ->
                val visibleMisses = cluster.deadlineMissFrameIds.filter(visibleFrameIds::contains)
                if (visibleMisses.isEmpty()) {
                    null
                } else {
                    val first = filtered.frames.first { it.sample.frameId == visibleMisses.first() }.sample
                    val last = filtered.frames.first { it.sample.frameId == visibleMisses.last() }.sample
                    cluster.copy(
                        firstFrameId = visibleMisses.first(),
                        lastFrameId = visibleMisses.last(),
                        deadlineMissFrameIds = visibleMisses,
                        durationNs = clusterDuration(first, last),
                    )
                }
            },
    )
}

internal fun AnalyzedFrame.isProblemFrame(): Boolean =
    deadlineVerdict == FrameDeadlineVerdict.MISSED ||
        sample.platformJank == true ||
        platformJankTypes.isNotEmpty() ||
        sample.jankStatsJank == true ||
        sample.jankStatsReasons.isNotEmpty()

internal fun AnalyzedFrame.problemSignal(language: UiLanguage): String =
    buildList {
        if (deadlineVerdict == FrameDeadlineVerdict.MISSED) {
            add(localizedStringResource(Res.string.deadline_miss_signal, language))
        }
        if (sample.platformJank == true || platformJankTypes.isNotEmpty()) {
            add(localizedStringResource(Res.string.platform_jank, language))
        }
        if (sample.jankStatsJank == true || sample.jankStatsReasons.isNotEmpty()) {
            add(localizedStringResource(Res.string.jank_stats, language))
        }
    }.joinToString(" · ").ifEmpty { "—" }

internal fun AnalyzedFrame.problemEvidence(language: UiLanguage): String =
    buildList {
        add(problemSignal(language))
        sample.resolvedDurationNs()?.let {
            add("${localizedStringResource(Res.string.duration, language)}: ${it.formatMillis()}")
        }
        sample.knownExpectedDurationNs()?.let { budget ->
            add("${localizedStringResource(Res.string.budget, language)}: ${budget.formatMillis()}")
            val duration = sample.resolvedDurationNs()
            if (duration != null && duration > budget) {
                val overBudget = duration - budget
                add(
                    localizedStringResource(
                        Res.string.over_budget_by,
                        language,
                        overBudget.formatMillis(),
                    ),
                )
            }
        }
        missedVsyncCount?.takeIf { it > 0 }?.let {
            add("${localizedStringResource(Res.string.missed_vsync, language)}: $it")
        }
        if (platformJankTypes.isNotEmpty()) {
            add(
                "${localizedStringResource(Res.string.jank_types, language)}: " +
                    platformJankTypes.joinToString { it.displayName(language) },
            )
        }
        if (sample.jankStatsReasons.isNotEmpty()) {
            add(
                "${localizedStringResource(Res.string.jank_stats_reasons, language)}: " +
                    sample.jankStatsReasons.joinToString(),
            )
        }
    }.joinToString(" · ")

private fun JankType.displayName(language: UiLanguage): String =
    when (this) {
        JankType.PLATFORM_REPORTED -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "平台报告" else "Platform reported"
        JankType.DEADLINE_MISSED -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "超出帧预算" else "Deadline missed"
        JankType.SLOW_INPUT -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "输入耗时" else "Slow input"
        JankType.SLOW_ANIMATION -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "动画耗时" else "Slow animation"
        JankType.SLOW_LAYOUT -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "布局耗时" else "Slow layout"
        JankType.SLOW_DRAW -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "绘制耗时" else "Slow draw"
        JankType.SLOW_SYNC -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "同步耗时" else "Slow sync"
        JankType.SLOW_COMMAND -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "命令耗时" else "Slow command"
        JankType.SLOW_SWAP -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "交换缓冲耗时" else "Slow swap"
        JankType.UNKNOWN -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "未知类型" else "Unknown type"
    }

internal fun FrameSource.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            FrameSource.JANK_STATS -> Res.string.frame_source_jank_stats
            FrameSource.FRAME_METRICS -> Res.string.frame_source_frame_metrics
            FrameSource.GFXINFO -> Res.string.frame_source_gfxinfo
            FrameSource.PERFETTO -> Res.string.frame_source_perfetto
        },
        language,
    )

internal fun FrameViewFilter.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            FrameViewFilter.ALL -> Res.string.all_frames
            FrameViewFilter.JANK_ONLY -> Res.string.jank_only
            FrameViewFilter.DEADLINE_MISSES -> Res.string.deadline_misses
        },
        language,
    )

private fun AnalyzedFrame.matches(filter: FrameViewFilter): Boolean =
    when (filter) {
        FrameViewFilter.ALL -> true
        FrameViewFilter.JANK_ONLY -> isProblemFrame()
        FrameViewFilter.DEADLINE_MISSES -> deadlineVerdict == FrameDeadlineVerdict.MISSED
    }

private fun clusterDuration(
    first: FrameSample,
    last: FrameSample,
): Long {
    val start = first.intendedVsyncNs
    val end = last.presentNs ?: last.frameCompletedNs
    return if (start != null && end != null && end >= start) end - start else last.resolvedDurationNs() ?: 0L
}
