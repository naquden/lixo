package se.lixo.app.verify

import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies feature 048 (doc/plans/048-version-0-1-0.md) against the installed app: the
 * versionName is "0.1.0" and the versionCode (build number) stays at 1.
 */
class AppVersionTest {

    @Test
    fun installedApp_hasVersionName010_andVersionCode1() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        assertEquals("0.1.0", info.versionName)
        assertEquals(1L, PackageInfoCompat.getLongVersionCode(info))
    }
}
