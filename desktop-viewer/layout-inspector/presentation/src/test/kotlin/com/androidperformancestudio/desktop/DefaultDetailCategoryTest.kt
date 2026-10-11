package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultDetailCategoryTest {
    private val withCompose = listOf(DetailCategory.OVERVIEW, DetailCategory.COMPOSE, DetailCategory.EVIDENCE)

    @Test
    fun `a composable opens the Compose tab`() {
        assertEquals(DetailCategory.COMPOSE, defaultDetailCategory("compose-inspection:42", withCompose))
    }

    @Test
    fun `a composable without Compose sections falls back to the first tab`() {
        assertEquals(
            DetailCategory.OVERVIEW,
            defaultDetailCategory("compose-inspection:42", listOf(DetailCategory.OVERVIEW, DetailCategory.EVIDENCE)),
        )
    }

    @Test
    fun `a view opens the first tab even when a Compose tab exists`() {
        assertEquals(DetailCategory.OVERVIEW, defaultDetailCategory("view:3", withCompose))
    }

    @Test
    fun `an unselected node opens the first tab`() {
        assertEquals(DetailCategory.OVERVIEW, defaultDetailCategory(null, withCompose))
    }

    @Test
    fun `no available category still yields the overview`() {
        assertEquals(DetailCategory.OVERVIEW, defaultDetailCategory(null, emptyList()))
    }
}
