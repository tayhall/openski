package com.openski.android

import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Release-only checks. Skipped in normal runs; set the environment variable (or system property)
 * OPENSKI_RELEASE_CHECK=1 before `gradlew :app:testDebugUnitTest` to make them fail on placeholders.
 */
class ReleaseCheckTest {
    private val enabled = !System.getenv("OPENSKI_RELEASE_CHECK").isNullOrBlank() ||
        !System.getProperty("OPENSKI_RELEASE_CHECK").isNullOrBlank()

    @Test fun userAgentContactIsReal() {
        assumeTrue("OPENSKI_RELEASE_CHECK not set", enabled)
        assertFalse("Set HttpResortFetcher.CONTACT to a real contact before release",
            HttpResortFetcher.CONTACT.endsWith(".invalid"))
    }
}
