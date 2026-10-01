package io.github.HanJason233.chimera.feature.nodegrouping.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.HanJason233.chimera.data.model.SubscriptionGroup

class SubscriptionGroupingStrategyTest {
    private val groups = listOf(
        SubscriptionGroup(sourceId = "a", name = "订阅 A"),
        SubscriptionGroup(sourceId = "b", name = "订阅 B")
    )

    @Test
    fun `保留仍然存在的订阅选择`() {
        assertEquals("b", SubscriptionGroupingStrategy.resolveActiveSourceId(groups, "b"))
    }

    @Test
    fun `订阅失效时回退到第一组`() {
        assertEquals("a", SubscriptionGroupingStrategy.resolveActiveSourceId(groups, "missing"))
    }

    @Test
    fun `没有订阅时返回空`() {
        assertNull(SubscriptionGroupingStrategy.resolveActiveSourceId(emptyList(), "missing"))
    }
}
