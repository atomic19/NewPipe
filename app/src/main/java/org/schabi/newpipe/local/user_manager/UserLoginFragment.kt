package org.schabi.newpipe.local.user_manager

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.SparseItemUtil

class UserLoginFragment : Fragment() {
    private var webView: WebView? = null
    private var lastOpenedVideoId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_user_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity?.title = "YouTube"

        webView = view.findViewById<WebView>(R.id.user_login_webview).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    Log.d(TAG, "Loading URL: ${request.url}")
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    Log.d(TAG, "Started URL: $url")
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "Finished URL: $url")
                    super.onPageFinished(view, url)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    Log.d(TAG, "Visited URL: $url (reload=$isReload)")
                    maybeOpenVideo(url)
                    super.doUpdateVisitedHistory(view, url, isReload)
                }

                override fun onLoadResource(view: WebView, url: String) {
//                    Log.d(TAG, "Resource URL: $url")
                    super.onLoadResource(view, url)
                }
            }
            loadUrl("https://www.youtube.com/")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView?.stopLoading()
        webView = null
    }

    private fun maybeOpenVideo(url: String) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        if (host != "m.youtube.com") return
        if (uri.path != "/watch") return
        val videoId = uri.getQueryParameter("v") ?: return
        if (videoId == lastOpenedVideoId) return
        if (!isAdded) return

        lastOpenedVideoId = videoId
        schedulePauseAndMute()
        val cleanedUrl = stripListParam(url)
        Log.d(TAG, "Opening URL: $cleanedUrl")
        openOrEnqueueVideo(cleanedUrl)
    }

    private fun stripListParam(url: String): String {
        val uri = Uri.parse(url)
        if (!uri.queryParameterNames.contains("list")) {
            return url
        }
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == "list") continue
            for (value in uri.getQueryParameters(name)) {
                builder.appendQueryParameter(name, value)
            }
        }
        return builder.build().toString()
    }

    private fun schedulePauseAndMute() {
        val view = webView ?: return
        val script = "document.querySelectorAll('video').forEach(function(v){v.pause();v.muted=true;});"
        var attemptsLeft = 120
        val runnable = object : Runnable {
            override fun run() {
                view.evaluateJavascript(script, null)
                attemptsLeft -= 1
                if (attemptsLeft > 0) {
                    view.postDelayed(this, 500L)
                }
            }
        }
        view.post(runnable)
    }

    private fun openOrEnqueueVideo(url: String) {
        val context = requireContext()
        if (PlayerHolder.getInstance().isPlayerOpen()) {
            SparseItemUtil.fetchStreamInfoAndSaveToDatabase(
                context,
                ServiceList.YouTube.serviceId,
                url
            ) { streamInfo ->
                if (!isAdded) return@fetchStreamInfoAndSaveToDatabase
                NavigationHelper.enqueueOnPlayer(context, SinglePlayQueue(streamInfo))
            }
            return
        }

        NavigationHelper.openVideoDetailFragment(
            context,
            requireActivity().supportFragmentManager,
            ServiceList.YouTube.serviceId,
            url,
            "Video",
            null,
            false
        )
    }

    companion object {
        private const val TAG = "UserLoginFragment"
    }
}
