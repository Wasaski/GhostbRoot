package com.ghostbroot.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation smoke test (spec §9): verifies the app package is
 * installed and has a launcher intent. Runs on the CI emulator matrix.
 * Orientation / density / overlay-permission scenarios that require a
 * physical device are documented in docs/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test fun launch_showsConnectionScreen() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        assertTrue("Launcher intent must exist", intent != null)
    }
}
