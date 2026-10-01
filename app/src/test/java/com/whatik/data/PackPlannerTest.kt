package com.whatik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPlannerTest {

    private fun items(n: Int, animated: Boolean, prefix: String = if (animated) "a" else "s") =
        List(n) { PackPlanner.Item("$prefix$it", animated) }

    @Test
    fun singleStickerMakesOneNewPackFlaggedTooSmall() {
        val plan = PackPlanner.plan(items(1, false), convertAnimatedToStatic = false)
        assertEquals(1, plan.packs.size)
        assertTrue(plan.packs[0].isNew)
        assertEquals(1, plan.tooSmall.size)
        assertFalse(plan.packs[0].isAddable)
    }

    @Test
    fun splitsIntoChunksOfThirty() {
        val plan = PackPlanner.plan(items(65, false), convertAnimatedToStatic = false)
        assertEquals(listOf(30, 30, 5), plan.packs.map { it.items.size })
        assertTrue(plan.tooSmall.isEmpty())
    }

    @Test
    fun rebalancesLastPackAboveMinimum() {
        val plan = PackPlanner.plan(items(31, false), convertAnimatedToStatic = false)
        assertEquals(listOf(28, 3), plan.packs.map { it.items.size })
        assertTrue(plan.tooSmall.isEmpty())
        // nessuno sticker perso o duplicato
        assertEquals(31, plan.packs.flatMap { it.items }.map { it.id }.toSet().size)
    }

    @Test
    fun separatesStaticAndAnimated() {
        val plan = PackPlanner.plan(items(5, false) + items(4, true), convertAnimatedToStatic = false)
        assertEquals(2, plan.packs.size)
        assertEquals(5, plan.packs.first { !it.animated }.items.size)
        assertEquals(4, plan.packs.first { it.animated }.items.size)
    }

    @Test
    fun convertAnimatedToStaticMergesGroups() {
        val plan = PackPlanner.plan(items(5, false) + items(4, true), convertAnimatedToStatic = true)
        assertEquals(1, plan.packs.size)
        assertFalse(plan.packs[0].animated)
        assertEquals(9, plan.packs[0].items.size)
    }

    @Test
    fun fillsExistingPackThenOverflowsIntoNewOnes() {
        val target = PackPlanner.Target("p1", "Pack", animated = false, stickerCount = 28)
        val plan = PackPlanner.plan(items(12, false), convertAnimatedToStatic = false, staticTarget = target)
        assertEquals(2, plan.packs.size)
        assertEquals("p1", plan.packs[0].existingIdentifier)
        assertEquals(2, plan.packs[0].items.size)
        assertEquals(30, plan.packs[0].resultingCount)
        assertNull(plan.packs[1].existingIdentifier)
        assertEquals(10, plan.packs[1].items.size)
    }

    @Test
    fun existingTargetOfWrongKindIsIgnored() {
        val target = PackPlanner.Target("p1", "Pack", animated = true, stickerCount = 2)
        val plan = PackPlanner.plan(items(4, false), convertAnimatedToStatic = false, staticTarget = target)
        assertEquals(1, plan.packs.size)
        assertTrue(plan.packs[0].isNew)
    }

    @Test
    fun existingTargetBelowMinimumStaysFlagged() {
        val target = PackPlanner.Target("p1", "Pack", animated = false, stickerCount = 1)
        val plan = PackPlanner.plan(items(1, false), convertAnimatedToStatic = false, staticTarget = target)
        assertEquals(2, plan.packs[0].resultingCount)
        assertEquals(1, plan.tooSmall.size)
    }

    @Test
    fun packNamesAreDistinctAndDescriptive() {
        val plan = PackPlanner.plan(items(35, false) + items(3, true), convertAnimatedToStatic = false)
        val names = plan.packs.map { PackPlanner.packName("TikTok", plan, it) }
        assertEquals(listOf("TikTok statici 1", "TikTok statici 2", "TikTok animati"), names)
        val single = PackPlanner.plan(items(3, false), convertAnimatedToStatic = false)
        assertEquals("TikTok", PackPlanner.packName("TikTok", single, single.packs[0]))
        assertEquals("Sticker", PackPlanner.packName("   ", single, single.packs[0]))
    }
}
