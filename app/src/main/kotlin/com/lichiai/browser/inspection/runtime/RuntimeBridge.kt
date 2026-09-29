package com.lichiai.browser.inspection.runtime

import android.webkit.JavascriptInterface
import com.lichiai.browser.inspection.network.NetworkObserver
import org.json.JSONObject

class BrowserInspectionJsBridge(
    private val networkObserver: NetworkObserver,
    private val consoleInspector: ConsoleInspector
) {
    companion object {
        const val INTERFACE_NAME = "LichiInspectionBridge"

        val INSTRUMENTATION_SCRIPT = """
            (function() {
                if (window.__lichi_instrumented) return;
                window.__lichi_instrumented = true;

                // 1. Hook window.fetch
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        var args = Array.prototype.slice.call(arguments);
                        var input = args[0];
                        var init = args[1] || {};
                        var url = (typeof input === 'string') ? input : (input ? input.url : '');
                        var method = (init.method || (input && input.method) || 'GET').toUpperCase();
                        var startTime = Date.now();
                        var reqBody = '';
                        try {
                            if (init.body) {
                                reqBody = (typeof init.body === 'string') ? init.body : '[Binary/Object Body]';
                            }
                        } catch(e) {}

                        var reqHeaders = {};
                        try {
                            if (init.headers) {
                                if (init.headers.forEach) {
                                    init.headers.forEach(function(val, key) { reqHeaders[key] = val; });
                                } else if (typeof init.headers === 'object') {
                                    reqHeaders = init.headers;
                                }
                            }
                        } catch(e) {}

                        return origFetch.apply(window, args).then(function(response) {
                            var duration = Date.now() - startTime;
                            var respStatus = response.status;
                            var respStatusText = response.statusText || 'OK';
                            var respHeaders = {};
                            try {
                                if (response.headers && response.headers.forEach) {
                                    response.headers.forEach(function(v, k) { respHeaders[k] = v; });
                                }
                            } catch(e) {}

                            // Clone response to inspect body without consuming the user's stream
                            var clone = response.clone();
                            clone.text().then(function(bodyText) {
                                try {
                                    if (window.LichiInspectionBridge) {
                                        window.LichiInspectionBridge.onFetchCompleted(
                                            url,
                                            method,
                                            JSON.stringify(reqHeaders),
                                            reqBody.substring(0, 500),
                                            respStatus,
                                            respStatusText,
                                            duration,
                                            JSON.stringify(respHeaders),
                                            bodyText.substring(0, 1500)
                                        );
                                    }
                                } catch(err) {}
                            }).catch(function() {
                                if (window.LichiInspectionBridge) {
                                    window.LichiInspectionBridge.onFetchCompleted(
                                        url, method, JSON.stringify(reqHeaders), reqBody.substring(0, 500),
                                        respStatus, respStatusText, duration, JSON.stringify(respHeaders), ''
                                    );
                                }
                            });

                            return response;
                        }).catch(function(err) {
                            var duration = Date.now() - startTime;
                            try {
                                if (window.LichiInspectionBridge) {
                                    window.LichiInspectionBridge.onFetchCompleted(
                                        url, method, JSON.stringify(reqHeaders), reqBody.substring(0, 500),
                                        0, err.toString(), duration, '{}', ''
                                    );
                                }
                            } catch(e) {}
                            throw err;
                        });
                    };
                }

                // 2. Hook XMLHttpRequest
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                var origSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;

                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__lichi_method = (method || 'GET').toUpperCase();
                    this.__lichi_url = url || '';
                    this.__lichi_headers = {};
                    this.__lichi_startTime = Date.now();
                    return origOpen.apply(this, arguments);
                };

                XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                    if (this.__lichi_headers) {
                        this.__lichi_headers[header] = value;
                    }
                    return origSetRequestHeader.apply(this, arguments);
                };

                XMLHttpRequest.prototype.send = function(body) {
                    var xhr = this;
                    var reqBodyStr = '';
                    try {
                        if (body && typeof body === 'string') reqBodyStr = body.substring(0, 500);
                    } catch(e) {}

                    xhr.addEventListener('loadend', function() {
                        try {
                            var duration = Date.now() - (xhr.__lichi_startTime || Date.now());
                            var respText = '';
                            try {
                                if (xhr.responseType === '' || xhr.responseType === 'text' || xhr.responseType === 'json') {
                                    respText = (typeof xhr.response === 'string') ? xhr.response : JSON.stringify(xhr.response);
                                }
                            } catch(e) {}

                            if (window.LichiInspectionBridge) {
                                window.LichiInspectionBridge.onXhrCompleted(
                                    xhr.__lichi_url || '',
                                    xhr.__lichi_method || 'GET',
                                    JSON.stringify(xhr.__lichi_headers || {}),
                                    reqBodyStr,
                                    xhr.status,
                                    xhr.statusText || 'OK',
                                    duration,
                                    xhr.getAllResponseHeaders ? xhr.getAllResponseHeaders() : '',
                                    (respText || '').substring(0, 1500)
                                );
                            }
                        } catch(e) {}
                    });

                    return origSend.apply(this, arguments);
                };

                // 3. Hook WebSocket
                if (window.WebSocket) {
                    var origWs = window.WebSocket;
                    window.WebSocket = function(url, protocols) {
                        try {
                            if (window.LichiInspectionBridge) {
                                window.LichiInspectionBridge.onWebSocketConnected(url, Array.isArray(protocols) ? protocols.join(',') : (protocols || ''));
                            }
                        } catch(e) {}
                        return new origWs(url, protocols);
                    };
                    window.WebSocket.prototype = origWs.prototype;
                }

                // 4. Global Error Handlers
                window.addEventListener('error', function(e) {
                    try {
                        if (window.LichiInspectionBridge) {
                            window.LichiInspectionBridge.onRuntimeError(
                                e.message || 'Script error',
                                e.filename || '',
                                e.lineno || 0,
                                e.colno || 0
                            );
                        }
                    } catch(err) {}
                });

                window.addEventListener('unhandledrejection', function(e) {
                    try {
                        var reason = e.reason ? (e.reason.message || e.reason.toString()) : 'Unhandled Promise Rejection';
                        if (window.LichiInspectionBridge) {
                            window.LichiInspectionBridge.onRuntimeError(reason, '', 0, 0);
                        }
                    } catch(err) {}
                });
            })();
        """.trimIndent()
    }

    @JavascriptInterface
    fun onFetchCompleted(
        url: String,
        method: String,
        headersJson: String,
        bodyPreview: String,
        statusCode: Int,
        statusText: String,
        durationMs: Long,
        responseHeadersJson: String,
        responseBodyPreview: String
    ) {
        val reqHeaders = parseHeaders(headersJson)
        val respHeaders = parseHeaders(responseHeadersJson)
        networkObserver.recordJsFetchOrXhr(
            url = url,
            method = method,
            headers = reqHeaders,
            requestBody = bodyPreview.ifBlank { null },
            statusCode = statusCode,
            statusText = statusText,
            durationMs = durationMs,
            responseHeaders = respHeaders,
            responseBody = responseBodyPreview.ifBlank { null },
            initiator = "Fetch"
        )
    }

    @JavascriptInterface
    fun onXhrCompleted(
        url: String,
        method: String,
        headersJson: String,
        bodyPreview: String,
        statusCode: Int,
        statusText: String,
        durationMs: Long,
        rawResponseHeaders: String,
        responseBodyPreview: String
    ) {
        val reqHeaders = parseHeaders(headersJson)
        val respHeaders = parseRawHeaderLines(rawResponseHeaders)
        networkObserver.recordJsFetchOrXhr(
            url = url,
            method = method,
            headers = reqHeaders,
            requestBody = bodyPreview.ifBlank { null },
            statusCode = statusCode,
            statusText = statusText,
            durationMs = durationMs,
            responseHeaders = respHeaders,
            responseBody = responseBodyPreview.ifBlank { null },
            initiator = "XHR"
        )
    }

    @JavascriptInterface
    fun onWebSocketConnected(url: String, protocols: String) {
        networkObserver.recordRequest(
            url = url,
            method = "WS",
            headers = mapOf("Sec-WebSocket-Protocol" to protocols),
            isForMainFrame = false,
            hasUserGesture = false
        )
    }

    @JavascriptInterface
    fun onRuntimeError(message: String, source: String, lineno: Int, colno: Int) {
        consoleInspector.recordJsRuntimeError(message, source, lineno, colno)
    }

    private fun parseHeaders(jsonStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(jsonStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.optString(k, "")
            }
        } catch (_: Exception) {}
        return result
    }

    private fun parseRawHeaderLines(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        raw.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                map[key] = value
            }
        }
        return map
    }
}
