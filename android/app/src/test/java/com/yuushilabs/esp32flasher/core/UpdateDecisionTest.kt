package com.yuushilabs.esp32flasher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDecisionTest {
    @Test
    fun `compares plain semantic versions`() {
        assertTrue(UpdateDecision.compareVersions("0.2.0", "0.1.0") > 0)
        assertTrue(UpdateDecision.compareVersions("0.1.0", "0.2.0") < 0)
        assertEquals(0, UpdateDecision.compareVersions("0.1.0", "0.1.0"))
        assertTrue(UpdateDecision.compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(UpdateDecision.compareVersions("1.0", "0.9.9") > 0)
    }

    @Test
    fun `ignores a leading v prefix and build metadata`() {
        assertEquals(0, UpdateDecision.compareVersions("v1.2.3", "1.2.3"))
        assertEquals(0, UpdateDecision.compareVersions("1.2.3+build.7", "1.2.3"))
        assertTrue(UpdateDecision.compareVersions("V2.0.0", "v1.9.9") > 0)
    }

    @Test
    fun `prerelease sorts before the release`() {
        assertTrue(UpdateDecision.compareVersions("1.0.0-rc1", "1.0.0") < 0)
        assertTrue(UpdateDecision.compareVersions("1.0.0", "1.0.0-rc1") > 0)
        assertTrue(UpdateDecision.compareVersions("1.0.0-rc2", "1.0.0-rc1") > 0)
        assertTrue(UpdateDecision.compareVersions("1.0.0-beta", "1.0.0-rc1") < 0)
    }

    @Test
    fun `unparsable versions compare as equal`() {
        assertEquals(0, UpdateDecision.compareVersions("nightly", "canary"))
        assertTrue(UpdateDecision.compareVersions("1.0.0", "nightly") > 0)
        assertTrue(UpdateDecision.compareVersions("nightly", "1.0.0") < 0)
    }

    @Test
    fun `version code in the release notes wins`() {
        val outcome = UpdateDecision.decide(
            UpdateCheck(
                currentVersionCode = 3,
                currentVersionName = "0.1.0",
                latest = ReleaseVersion("0.1.0", versionCode = 4),
                apkUrl = "https://example.test/app.apk",
            ),
        )
        assertTrue(outcome is UpdateOutcome.Available)
        assertEquals("https://example.test/app.apk", (outcome as UpdateOutcome.Available).apkUrl)
    }

    @Test
    fun `lower version code means up to date`() {
        val outcome = UpdateDecision.decide(
            UpdateCheck(
                currentVersionCode = 5,
                currentVersionName = "0.1.0",
                latest = ReleaseVersion("9.9.9", versionCode = 4),
            ),
        )
        assertEquals(UpdateOutcome.UpToDate, outcome)
    }

    @Test
    fun `an equal version code is up to date even when the tag looks newer`() {
        val outcome = UpdateDecision.decide(
            UpdateCheck(
                currentVersionCode = 5,
                currentVersionName = "1.0.0",
                latest = ReleaseVersion("2.0.0", versionCode = 5),
            ),
        )
        assertEquals(UpdateOutcome.UpToDate, outcome)
    }

    @Test
    fun `falls back to semantic versions without a version code`() {
        val newer = UpdateDecision.decide(
            UpdateCheck(1, "0.1.0", ReleaseVersion("v0.2.0")),
        )
        assertTrue(newer is UpdateOutcome.Available)
        assertEquals("versionName", (newer as UpdateOutcome.Available).reason)

        val same = UpdateDecision.decide(
            UpdateCheck(1, "0.2.0", ReleaseVersion("v0.2.0")),
        )
        assertEquals(UpdateOutcome.UpToDate, same)

        val older = UpdateDecision.decide(
            UpdateCheck(1, "0.3.0", ReleaseVersion("v0.2.0")),
        )
        assertEquals(UpdateOutcome.UpToDate, older)
    }

    @Test
    fun `not modified and missing releases are reported`() {
        assertEquals(
            UpdateOutcome.NotModified,
            UpdateDecision.decide(UpdateCheck(1, "0.1.0", ReleaseVersion("9.9.9"), notModified = true)),
        )
        assertEquals(
            UpdateOutcome.NoRelease,
            UpdateDecision.decide(UpdateCheck(1, "0.1.0", null)),
        )
    }

    @Test
    fun `extracts a version code marker from release notes`() {
        assertEquals(42, UpdateDecision.parseVersionCode("release\nversionCode=42\nnotes"))
        assertEquals(7, UpdateDecision.parseVersionCode("versionCode: 7"))
        assertEquals(12, UpdateDecision.parseVersionCode("Versioncode = 12"))
        assertNull(UpdateDecision.parseVersionCode(null))
        assertNull(UpdateDecision.parseVersionCode("no marker here"))
    }

    @Test
    fun `normalizes version names`() {
        assertEquals("1.2.3", UpdateDecision.normalizeVersionName("v1.2.3"))
        assertEquals("1.2.3", UpdateDecision.normalizeVersionName(" V1.2.3 "))
    }
}
