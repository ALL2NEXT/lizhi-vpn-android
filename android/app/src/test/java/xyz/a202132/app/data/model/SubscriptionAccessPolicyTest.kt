package xyz.a202132.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAccessPolicyTest {

    @Test
    fun minimumAccountTypeUsesBannedGuestFormalOrder() {
        val guestLine = SubscriptionSource(
            id = "1",
            name = "游客线路",
            url = "https://example.com/guest",
            minAccountType = ACCOUNT_TYPE_GUEST,
        )
        val formalLine = guestLine.copy(minAccountType = ACCOUNT_TYPE_FORMAL)

        assertFalse(guestLine.canBeRequestedBy(ACCOUNT_TYPE_BANNED))
        assertTrue(guestLine.canBeRequestedBy(ACCOUNT_TYPE_GUEST))
        assertTrue(guestLine.canBeRequestedBy(ACCOUNT_TYPE_FORMAL))
        assertFalse(formalLine.canBeRequestedBy(ACCOUNT_TYPE_GUEST))
        assertTrue(formalLine.canBeRequestedBy(ACCOUNT_TYPE_FORMAL))
    }

    @Test
    fun bannedMinimumAllowsAllKnownAccountTypes() {
        val source = SubscriptionSource(
            id = "1",
            name = "公开线路",
            url = "https://example.com/public",
            minAccountType = ACCOUNT_TYPE_BANNED,
        )

        assertTrue(source.canBeRequestedBy(ACCOUNT_TYPE_BANNED))
        assertTrue(source.canBeRequestedBy(ACCOUNT_TYPE_GUEST))
        assertTrue(source.canBeRequestedBy(ACCOUNT_TYPE_FORMAL))
    }
}
