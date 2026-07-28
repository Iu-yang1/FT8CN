package com.bg7yoz.ft8cn.feature.shell

import android.content.Intent
import com.bg7yoz.ft8cn.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyConsoleLauncherTest {
    @Test
    fun onlyWhitelistedDestinationsAreResolved() {
        LegacyConsoleDestination.values().forEach { destination ->
            val intent = Intent().putExtra(
                LegacyConsoleLauncher.EXTRA_DESTINATION,
                destination.navigationId,
            )
            assertEquals(destination.navigationId, LegacyConsoleLauncher.resolveDestination(intent))
        }
        val invalid = Intent().putExtra(
            LegacyConsoleLauncher.EXTRA_DESTINATION,
            R.id.countFragment,
        )
        assertEquals(0, LegacyConsoleLauncher.resolveDestination(invalid))
        assertEquals(0, LegacyConsoleLauncher.resolveDestination(null))
    }
}
