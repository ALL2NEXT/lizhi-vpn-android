package xyz.a202132.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAccessPolicyTest {
    @Test
    fun lv1DoesNotRequestLv6Subscription() {
        assertFalse(source(minLevel = 6).canBeRequestedBy(accountLevel = 1))
    }

    @Test
    fun levelMeetingMinimumCanRequestSubscription() {
        assertTrue(source(minLevel = 6).canBeRequestedBy(accountLevel = 6))
    }

    @Test
    fun lv0CanRequestSubscriptionThatAllowsLv0() {
        assertTrue(source(minLevel = 0).canBeRequestedBy(accountLevel = 0))
    }

    @Test
    fun unknownAccountUsesLv0Comparison() {
        val unknownAccountLevel = 0
        assertTrue(source(minLevel = 0).canBeRequestedBy(unknownAccountLevel))
        assertFalse(source(minLevel = 1).canBeRequestedBy(unknownAccountLevel))
    }

    private fun source(minLevel: Int) = SubscriptionSource(
        id = "1",
        name = "测试线路",
        url = "https://example.com/sub",
        minLevel = minLevel
    )
}
