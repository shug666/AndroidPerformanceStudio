package com.androidperformancestudio.desktop

/**
 * Category a newly selected node opens on.
 *
 * A composable opens its Compose tab so PARAMETERS and MODIFIER are visible without a manual tab
 * click; every other selection opens its first available tab. A node without any Compose data has no
 * COMPOSE category to open, which is what keeps View and semantics-only selections on their own tabs.
 */
internal fun defaultDetailCategory(
    selectedNodeId: String?,
    categories: List<DetailCategory>,
): DetailCategory =
    if (selectedNodeId?.startsWith(COMPOSE_NODE_ID_PREFIX) == true && DetailCategory.COMPOSE in categories) {
        DetailCategory.COMPOSE
    } else {
        categories.firstOrNull() ?: DetailCategory.OVERVIEW
    }
