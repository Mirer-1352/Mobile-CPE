package dev.autohotspot

import org.junit.Assert.*
import org.junit.Test

class HomeNavigationPolicyTest {
    @Test fun homeIntentDuringRotationRestoresThePanelInsteadOfOpeningTheOtherLauncher() {
        assertFalse(HomeNavigationPolicy.redirects(true, false, 1200, 1000, recreated = true))
        assertTrue(HomeNavigationPolicy.redirects(true, false, 1200, 1000))
    }
    @Test fun coldStartAndOldPausesDoNotLookLikeASecondHomeGesture() {
        assertFalse(HomeNavigationPolicy.redirects(true, false, 1200, -1))
        assertFalse(HomeNavigationPolicy.redirects(true, false, 3000, 1000))
        assertFalse(HomeNavigationPolicy.redirects(false, true, 1200, 1000))
        assertTrue(HomeNavigationPolicy.redirects(true, true, 3000, -1))
    }
}
