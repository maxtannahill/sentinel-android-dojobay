package com.samourai.sentinel.ui.tools

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.tools.sweep.SweepPrivKeyFragment
import com.samourai.sentinel.ui.utils.SingleFlightAction

class ToolsFragment : PreferenceFragmentCompat() {
    private val broadcastLaunch = SingleFlightAction()

    override fun onResume() {
        super.onResume()
        broadcastLaunch.reset()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.tools_preferences, rootKey)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setTransparencyToIcons()

        findPreference<Preference>("sweep")
            ?.setOnPreferenceClickListener {
                // Commit synchronously so another queued tap sees the same sheet.
                if (!parentFragmentManager.isStateSaved &&
                    parentFragmentManager.findFragmentByTag(TAG_SWEEP) == null
                ) {
                    SweepPrivKeyFragment().showNow(parentFragmentManager, TAG_SWEEP)
                }
                true
            }
        findPreference<Preference>("broadcastTx")
            ?.setOnPreferenceClickListener {
                broadcastLaunch.run {
                    startActivity(Intent(requireActivity(), BroadcastTx::class.java))
                }
                true
            }
    }

    companion object {
        private const val TAG_SWEEP = "tools_sweep"
    }

    private fun setTransparencyToIcons() {
        findPreference<Preference>("sweep")!!.icon.let {
            it!!.alpha = 150
        }

        findPreference<Preference>("broadcastTx")!!.icon.let {
            it!!.alpha = 150
        }
    }

}