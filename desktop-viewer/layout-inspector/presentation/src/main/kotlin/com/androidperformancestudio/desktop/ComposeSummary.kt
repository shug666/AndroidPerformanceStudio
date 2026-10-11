package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.ComposableDetail

/**
 * Condensed view of one composable's full-fidelity detail.
 *
 * The Compose tab keeps the complete PARAMETERS and MODIFIERS lists. The overview tab is where a
 * composable is first looked at, and what matters there is which parameters are lambdas - a lambda's
 * definition site is what decides whether a recomposition can skip the call - plus whether the node
 * carries modifiers or semantics at all.
 *
 * A modifier value is reported empty by the Compose 1.10.4 inspector, so only its presence is known;
 * the section deliberately shows a count instead of inventing a chain.
 */
internal data class ComposeSummary(
    val parameterCount: Int,
    val lambdaParameters: List<LambdaParameter>,
    val lambdaCount: Int,
    val modifierCount: Int,
    val mergedSemanticsCount: Int,
    val unmergedSemanticsCount: Int,
) {
    /** A lambda parameter plus the site its lambda was defined at, when the inspector reported one. */
    data class LambdaParameter(val name: String, val location: String?)
}

/** Detail value type the Compose inspector uses for lambda parameters. */
internal const val LAMBDA_PARAMETER_TYPE = "LAMBDA"

/** Lambdas listed inline on the overview before the rest is left to the Compose tab. */
internal const val MAX_SUMMARY_LAMBDAS = 5

/**
 * Compact tree marker for a composable: how many lambda parameters it takes, plus a warning when the
 * compiler report says it cannot skip or has unstable parameters. Empty when a node carries neither.
 */
internal fun composeTreeBadge(summary: ComposeSummary, risky: Boolean): String =
    buildString {
        if (summary.lambdaCount > 0) append('λ').append(summary.lambdaCount)
        if (risky) {
            if (isNotEmpty()) append(' ')
            append('⚠')
        }
    }

internal fun ComposableDetail?.toComposeSummary(): ComposeSummary {
    if (this == null) {
        return ComposeSummary(
            parameterCount = 0,
            lambdaParameters = emptyList(),
            lambdaCount = 0,
            modifierCount = 0,
            mergedSemanticsCount = 0,
            unmergedSemanticsCount = 0,
        )
    }
    val lambdas = parameters.filter { it.type == LAMBDA_PARAMETER_TYPE }
    return ComposeSummary(
        parameterCount = parameters.size,
        lambdaParameters = lambdas.take(MAX_SUMMARY_LAMBDAS).map { parameter ->
            ComposeSummary.LambdaParameter(
                name = parameter.name,
                location = parameter.value?.takeIf(String::isNotBlank),
            )
        },
        lambdaCount = lambdas.size,
        modifierCount = modifiers.size,
        mergedSemanticsCount = mergedSemantics.size,
        unmergedSemanticsCount = unmergedSemantics.size,
    )
}
