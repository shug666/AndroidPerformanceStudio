package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode

internal interface ComposeFrameCaptureClient {
    fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture

    fun captureTree(rootViewIds: List<Long>): ComposeInspectionFrame
}

internal data class StableComposeCapture(
    val views: ViewInspectionCapture,
    val compose: ComposeInspectionFrame,
)

/**
 * Obtains a frame using a bounded double-collect protocol.
 *
 * View Inspector and Compose Inspector do not expose a shared transaction or frame token. A
 * single View -> Compose sequence can therefore merge two different UI states. Collecting
 * View-A/Compose-A/View-B/Compose-B/View-C and requiring both structural reads to agree proves
 * that the returned middle view and second Compose tree were surrounded by the same stable state.
 *
 * A [ComposableRoot.viewId] is the id of the view a Compose tree hangs under (the
 * `AndroidComposeView`), not the window root that `GetComposablesCommand` is queried with, so it
 * is validated against the captured view tree instead of being compared with the requested roots.
 */
internal class StableComposeFrameCapture(
    private val client: ComposeFrameCaptureClient,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun capture(packageName: String): StableComposeCapture {
        var reason = "no attempt was made"
        repeat(maxAttempts) { attempt ->
            val viewA = client.captureViews(packageName, includeAttributes = false)
            val composeA = client.captureTree(viewA.rootViewIds)
            val viewB = client.captureViews(packageName, includeAttributes = false)
            val composeB = client.captureTree(viewB.rootViewIds)
            val viewC = client.captureViews(packageName, includeAttributes = false)

            val stableViews = viewA.structuralSnapshot() == viewB.structuralSnapshot() &&
                viewB.structuralSnapshot() == viewC.structuralSnapshot()
            val stableCompose = composeA.structuralRoots() == composeB.structuralRoots()
            val hostViewIds = viewB.hostViewIds()
            val anchoredRoots = composeB.roots.all { root -> root.viewId in hostViewIds }
            if (stableViews && stableCompose && anchoredRoots) {
                return StableComposeCapture(viewB, composeB)
            }
            reason =
                when {
                    !stableViews -> "view hierarchy kept changing"
                    !stableCompose -> "compose tree kept changing"
                    else ->
                        "compose roots " + composeB.roots.map { it.viewId } +
                            " are not views of the captured window"
                } + " (attempt " + (attempt + 1) + " of " + maxAttempts + ")"
        }
        error("Target changed during Compose frame capture; try again. Last attempt: " + reason)
    }

    private fun ViewInspectionCapture.structuralSnapshot() = snapshot.copy(capturedAtEpochMillis = 0)

    private fun ViewInspectionCapture.hostViewIds(): Set<Long> =
        (snapshot.windows.flatMap { window -> window.root.viewIds() } + snapshot.root.viewIds()).toSet()

    private fun UiNode.viewIds(): List<Long> {
        val collected = mutableListOf<Long>()
        val pending = ArrayDeque<UiNode>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node is ViewNode) {
                node.id.removePrefix("view:").toLongOrNull()?.let(collected::add)
            }
            pending.addAll(node.children)
        }
        return collected
    }

    private fun ComposeInspectionFrame.structuralRoots(): List<ComposableRoot> = roots.map { root ->
        root.copy(nodes = root.nodes.map { it.withoutObservationCounts() })
    }

    private fun ComposableNode.withoutObservationCounts(): ComposableNode = copy(
        recomposeCount = null,
        skipCount = null,
        children = children.map { it.withoutObservationCounts() },
    )

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
