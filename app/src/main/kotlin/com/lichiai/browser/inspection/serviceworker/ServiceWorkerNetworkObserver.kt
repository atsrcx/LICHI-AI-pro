package com.lichiai.browser.inspection.serviceworker

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.lichiai.browser.inspection.network.NetworkObserver

class ServiceWorkerNetworkObserver(private val networkObserver: NetworkObserver) {

    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
                val swController = ServiceWorkerControllerCompat.getInstance()
                swController.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        try {
                            val url = request.url.toString()
                            val method = request.method
                            val headers = request.requestHeaders ?: emptyMap()
                            networkObserver.recordRequest(
                                url = url,
                                method = method,
                                headers = headers,
                                isForMainFrame = request.isForMainFrame,
                                hasUserGesture = request.hasGesture()
                            )
                        } catch (_: Exception) {}
                        return null // Let normal SW execution proceed
                    }
                })
                isInitialized = true
            }
        } catch (_: Exception) {
            // Service worker interception unsupported or restricted in this environment
        }
    }
}
