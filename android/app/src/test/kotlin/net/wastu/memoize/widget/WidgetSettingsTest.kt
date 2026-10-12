package net.wastu.memoize.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetSettingsTest {
    @Test
    fun widgetsKeepIndependentTagFiltersAcrossSettingsInstances() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = WidgetSettings(context)
        settings.setTag(71, "work/projects")
        settings.setTag(72, "personal")
        val reopened = WidgetSettings(context)
        assertEquals("work/projects", reopened.tag(71))
        assertEquals("personal", reopened.tag(72))
        reopened.setTag(71, null)
        assertNull(settings.tag(71))
        assertEquals("personal", settings.tag(72))
        settings.remove(72)
        assertNull(reopened.tag(72))
    }
}
