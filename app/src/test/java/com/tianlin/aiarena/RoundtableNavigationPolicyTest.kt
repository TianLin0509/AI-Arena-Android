package com.tianlin.aiarena

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundtableNavigationPolicyTest {
    @Test
    fun firstUseBelowMinimumShowsConnectionGuide() {
        assertTrue(
            RoundtableNavigationPolicy.showConnectionGuide(
                usableCount = 1,
                connectionManagerRequested = false,
                roundtableUnlocked = false,
            ),
        )
    }

    @Test
    fun explicitPreviewCanOpenHomeBelowMinimum() {
        assertFalse(
            RoundtableNavigationPolicy.showConnectionGuide(
                usableCount = 0,
                connectionManagerRequested = false,
                roundtableUnlocked = true,
            ),
        )
    }

    @Test
    fun changingToLoggedOutMembersDoesNotEjectReturningUser() {
        assertFalse(
            RoundtableNavigationPolicy.showConnectionGuide(
                usableCount = 0,
                connectionManagerRequested = false,
                roundtableUnlocked = true,
            ),
        )
    }

    @Test
    fun explicitConnectionManagementAlwaysOpensGuide() {
        assertTrue(
            RoundtableNavigationPolicy.showConnectionGuide(
                usableCount = 3,
                connectionManagerRequested = true,
                roundtableUnlocked = true,
            ),
        )
    }

    @Test
    fun twoUsableProvidersOpenHomeWithoutPreview() {
        assertFalse(
            RoundtableNavigationPolicy.showConnectionGuide(
                usableCount = ArenaService.MIN_MEMBERS,
                connectionManagerRequested = false,
                roundtableUnlocked = false,
            ),
        )
    }
}
