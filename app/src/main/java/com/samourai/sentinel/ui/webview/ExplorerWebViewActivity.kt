package com.samourai.sentinel.ui.webview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.databinding.ActivityExplorerWebViewBinding
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import timber.log.Timber
import java.net.InetSocketAddress


class ExplorerWebViewActivity : AppCompatActivity() {

    lateinit var client: WebViewClient
    var tx: Tx? = null
    var url = ""
    lateinit var binding: ActivityExplorerWebViewBinding
    private var blockedDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExplorerWebViewBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (SentinelState.selectedTx != null) {
            tx = SentinelState.selectedTx!!
            title = tx!!.hash.split("-")[0]
        } else {
            finish()
        }

        binding.webView.setBackgroundColor(0)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
            WebViewCompat.startSafeBrowsing(applicationContext) { }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(binding.webView.settings, WebSettingsCompat.FORCE_DARK_ON)
        }
        // #472: Tor-required is part of every load decision. #241 already
        // fails closed on STARTING / ON-before-listener; this adds the OFF
        // and unsupported-proxy arms, which used to load direct even when
        // a paired Dojo or the Tor toggle requires Tor.
        val decision = explorerLoadDecision(
            proxyOverrideSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
            torReady = SentinelTorManager.isTorReady(),
            torOff = SentinelTorManager.getTorState().state == EnumTorState.OFF,
            torRequired = SentinelState.isTorRequired(),
        )
        when (decision) {
            ExplorerLoadDecision.LOAD_VIA_PROXY -> setProxy()
            // Tor optional and disabled: direct is the expected mode.
            ExplorerLoadDecision.LOAD_DIRECT -> load()
            // STARTING / ON-before-listener: defer, show the same progress
            // the page load uses - fetch nothing. The Tor observer's ON arm
            // completes the load once the listener is live.
            ExplorerLoadDecision.WAIT_FOR_TOR -> binding.progressWeb.visibility = View.VISIBLE
            ExplorerLoadDecision.BLOCK_TOR_OFF -> showBlocked(
                getString(R.string.explorer_tor_required_title),
                getString(R.string.explorer_tor_required_off),
                allowWait = true
            )
            ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED -> showBlocked(
                getString(R.string.explorer_tor_required_title),
                getString(R.string.explorer_tor_required_unsupported),
                allowWait = false
            )
            // Tor optional: going direct is the user's explicit choice.
            ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED ->
                this.showFloatingSnackBar(
                    binding.webView,
                    text = getString(R.string.explorer_proxy_unsupported_optional),
                    actionText = getString(R.string.explorer_continue_without_tor),
                    actionClick = { load() }
                )
        }
    }

    /**
     * #472: the explorer will not fetch anything. Explains why and offers
     * a safe exit; never a direct-load fallback. With [allowWait] the
     * screen stays open and the Tor observer loads through the proxy if
     * Tor becomes ready.
     */
    private fun showBlocked(title: String, message: String, allowWait: Boolean) {
        if (isFinishing || isDestroyed) return
        binding.progressWeb.visibility = View.INVISIBLE
        blockedDialog?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.explorer_close) { _, _ -> finish() }
        if (allowWait) {
            builder.setNegativeButton(R.string.explorer_wait_for_tor) { _, _ ->
                binding.progressWeb.visibility = View.VISIBLE
            }
        }
        blockedDialog = builder.show()
    }

    private fun setProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // Only reachable if support changed under us; never go direct.
            showBlocked(
                getString(R.string.explorer_tor_required_title),
                getString(R.string.explorer_tor_required_unsupported),
                allowWait = false
            )
            return
        }
        // #241: null must never reach the rule - callers gate on
        // isTorReady(); this re-reads and fails closed.
        val proxy = SentinelTorManager.getProxy() ?: run {
            Timber.w("Explorer proxy deferred: SOCKS listener not yet published (#241)")
            return
        }
        // androidx.webkit expects "socks://host:port" - the old
        // "SOCKS:/" + address() built "SOCKS:///127.0.0.1:9050" from
        // InetSocketAddress's leading slash (and "SOCKS:/null" on the
        // boot=5 window). Build the rule from the parsed fields.
        val address = proxy.address() as? InetSocketAddress ?: run {
            // #472: was a silent return - a spinner forever.
            proxyFailed(IllegalStateException("Tor proxy has no socket address"))
            return
        }
        // #252 regression fix: .hostName resolves. Android drops the
        // constructor's host string once it resolves, so the getter
        // falls through to a reverse-DNS lookup - a network call on
        // the main thread, NetworkOnMainThreadException at launch.
        // hostString never resolves and exists since API 24 (= minSdk).
        try {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks://${address.hostString}:${address.port}")
                .build()
            // #472: the old call passed load() as the Executor and an
            // empty listener. Now the listener loads, on Main, only once
            // the override is applied.
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                ContextCompat.getMainExecutor(this)
            ) {
                if (isFinishing || isDestroyed) return@setProxyOverride
                blockedDialog?.dismiss()
                blockedDialog = null
                load()
            }
        } catch (e: Exception) {
            // #472: an invalid rule or an unsupported override throws;
            // fail closed with an explanation instead of a silent spinner.
            proxyFailed(e)
        }
    }

    private fun proxyFailed(cause: Exception) {
        Timber.e(cause, "Explorer proxy override failed (#472)")
        showBlocked(
            getString(R.string.explorer_tor_route_failed_title),
            getString(R.string.explorer_tor_route_failed),
            allowWait = false
        )
    }



    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        binding.webView.settings.builtInZoomControls = true
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressWeb.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                Timber.i("onPageCommitVisible: ")
                binding.progressWeb.visibility = View.INVISIBLE
            }
        }
        tx?.let {
            url = intent.getStringExtra(EXTRA_URL)
                ?: ExplorerRepository().getExplorer(it.hash.split("-")[0])
            binding.webView.loadUrl(url)
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_explorer, menu)
        SentinelTorManager.getTorStateLiveData().observe(this) {
            menu.findItem(R.id.menu_web_tor).icon =
                ContextCompat.getDrawable(applicationContext, R.drawable.ic_tor_on)
            val icon = menu.findItem(R.id.menu_web_tor).icon
            when (it.state) {
                EnumTorState.STARTING -> {
                    icon?.setTint(ContextCompat.getColor(applicationContext, R.color.md_amber_300))
                }
                EnumTorState.ON -> {
                    icon?.setTint(ContextCompat.getColor(applicationContext, R.color.md_green_600))
                    // #241: ON fires at boot=5; only route through the proxy
                    // at full readiness. Until then this screen fetches
                    // nothing - the next state emission (listener published,
                    // boot=100) completes the load through the proxy.
                    // #472: unsupported devices were decided in onCreate.
                    if (SentinelTorManager.isTorReady() &&
                        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
                    ) {
                        setProxy()
                    }
                }
                EnumTorState.OFF -> {
                    menu.findItem(R.id.menu_web_tor).icon =
                        ContextCompat.getDrawable(applicationContext, R.drawable.ic_tor_disabled)
                    menu.findItem(R.id.menu_web_tor).icon?.setTint(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.md_grey_400
                        )
                    )
                }
                else -> {
                }
            }
        }
        return super.onCreateOptionsMenu(menu)
    }


    override fun onDestroy() {
        blockedDialog?.dismiss()
        blockedDialog = null
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
            }
            R.id.menu_web_copy_tx -> {
                tx?.hash?.let { copyText(it) }
            }
            R.id.menu_web_copy_url -> {
                binding.webView.url?.let { copyText(it) }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun copyText(string: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clipData = ClipData
                .newPlainText("", string)
        if (cm != null) {
            cm.setPrimaryClip(clipData)
            Toast.makeText(applicationContext, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /**
         * #107 tier 1: when present, load this URL instead of the
         * user's selected explorer. The entropy row's external
         * analysis link sets it; every other caller omits it and
         * gets unchanged explorer behavior.
         */
        const val EXTRA_URL = "com.samourai.sentinel.ui.webview.EXTRA_URL"
    }
}