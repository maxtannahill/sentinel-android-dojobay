package com.samourai.sentinel.ui.tools

import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.tools.sweep.SweepPrivKeyFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ToolsNavigationTest {
    @Test fun doubleBroadcastTapQueuesOnlyOneActivity() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()
        val fragment = tools(activity)
        repeat(2) { click(fragment, "broadcastTx") }
        val shadow = shadowOf(activity)
        assertEquals(BroadcastTx::class.java.name, shadow.nextStartedActivity.component?.className)
        assertNull(shadow.nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test fun returningFromBroadcastAllowsAnotherDeliberateLaunch() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()
        val fragment = tools(activity)
        click(fragment, "broadcastTx")
        assertNotNull(shadowOf(activity).nextStartedActivity)
        controller.pause().resume()
        click(fragment, "broadcastTx")
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test fun existingSweepDestinationIsNotOpenedAgain() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()
        val fragment = tools(activity)
        // A placeholder avoids inflating the key-entry UI or exercising sweep.
        activity.supportFragmentManager.beginTransaction().add(Fragment(), "tools_sweep").commitNow()
        repeat(2) { click(fragment, "sweep") }
        assertEquals(1, activity.supportFragmentManager.fragments.count { it.tag == "tools_sweep" })
        controller.pause().stop().destroy()
    }

    @Test fun doubleSweepTapFromEmptyOpensOnlyOneSheet() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()
        val fragment = tools(activity)
        assertEquals(0, sweepSheetCount(activity))
        // Two taps land before the fragment manager runs pending work.
        // show() would queue two commits; showNow plus the stable tag
        // leaves only one Sweep sheet.
        repeat(2) { click(fragment, "sweep") }
        activity.supportFragmentManager.executePendingTransactions()
        assertEquals(1, sweepSheetCount(activity))
        controller.pause().stop().destroy()
    }

    private fun tools(activity: HostActivity): ToolsFragment = ToolsFragment().also {
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, it).commitNow()
    }

    private fun sweepSheetCount(activity: HostActivity): Int =
        activity.supportFragmentManager.fragments.count { it is SweepPrivKeyFragment }

    private fun click(fragment: ToolsFragment, key: String) {
        val preference = fragment.findPreference<Preference>(key)!!
        preference.onPreferenceClickListener!!.onPreferenceClick(preference)
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.AppThemeV3)
            super.onCreate(state)
        }
    }
}
