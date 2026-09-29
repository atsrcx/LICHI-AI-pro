package com.lichiai.browser.inspection.dom

import android.webkit.WebView
import com.lichiai.browser.engine.ChromiumWebViewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DomInspector {

    companion object {
        val INSPECT_DOM_JS = """
            (function() {
                try {
                    // Meta tags
                    var metas = [];
                    var metaNodes = document.querySelectorAll('meta');
                    for (var i = 0; i < metaNodes.length; i++) {
                        var m = metaNodes[i];
                        metas.push({
                            name: m.getAttribute('name') || '',
                            property: m.getAttribute('property') || '',
                            content: m.getAttribute('content') || ''
                        });
                    }

                    // Forms
                    var forms = [];
                    var formNodes = document.querySelectorAll('form');
                    for (var f = 0; f < formNodes.length; f++) {
                        var formEl = formNodes[f];
                        var fields = [];
                        var inputs = formEl.querySelectorAll('input, select, textarea');
                        for (var inp = 0; inp < inputs.length; inp++) {
                            var el = inputs[inp];
                            var opts = [];
                            if (el.tagName.toLowerCase() === 'select') {
                                for (var o = 0; o < el.options.length; o++) {
                                    opts.push(el.options[o].text || el.options[o].value);
                                }
                            }
                            fields.push({
                                name: el.name || '',
                                type: el.type || el.tagName.toLowerCase(),
                                id: el.id || '',
                                placeholder: el.placeholder || '',
                                isRequired: el.required || false,
                                value: (el.value || '').substring(0, 100),
                                options: opts
                            });
                        }

                        var btnList = [];
                        var btns = formEl.querySelectorAll('button, input[type="submit"]');
                        for (var b = 0; b < btns.length; b++) {
                            btnList.push((btns[b].innerText || btns[b].value || 'Submit').trim());
                        }

                        forms.push({
                            formId: formEl.id || '',
                            formName: formEl.name || ('Form ' + (f + 1)),
                            action: formEl.action || window.location.href,
                            method: (formEl.method || 'GET').toUpperCase(),
                            enctype: formEl.enctype || '',
                            fields: fields,
                            submitButtons: btnList
                        });
                    }

                    // Iframes
                    var iframes = [];
                    var iframeNodes = document.querySelectorAll('iframe');
                    for (var ifr = 0; ifr < iframeNodes.length; ifr++) {
                        var node = iframeNodes[ifr];
                        var src = node.src || '';
                        var isCross = false;
                        try {
                            if (src) {
                                var u = new URL(src, window.location.href);
                                isCross = (u.origin !== window.location.origin);
                            }
                        } catch(e) {}
                        iframes.push({
                            frameId: node.id || ('iframe-' + ifr),
                            src: src,
                            name: node.name || '',
                            sandbox: node.getAttribute('sandbox') || '',
                            isCrossDomain: isCross
                        });
                    }

                    // Shadow DOM detection
                    var shadowRoots = [];
                    var allEls = document.querySelectorAll('*');
                    for (var s = 0; s < allEls.length; s++) {
                        if (allEls[s].shadowRoot) {
                            shadowRoots.push({
                                hostTag: allEls[s].tagName.toLowerCase(),
                                hostId: allEls[s].id || '',
                                mode: allEls[s].shadowRoot.mode || 'open',
                                childrenCount: allEls[s].shadowRoot.children.length
                            });
                        }
                    }

                    // Headings
                    var headings = [];
                    var hNodes = document.querySelectorAll('h1, h2, h3');
                    for (var h = 0; h < hNodes.length && headings.length < 15; h++) {
                        var hText = (hNodes[h].innerText || '').trim();
                        if (hText) {
                            headings.push(hNodes[h].tagName + ': ' + hText.substring(0, 80));
                        }
                    }

                    return JSON.stringify({
                        title: document.title || '',
                        charset: document.characterSet || 'UTF-8',
                        doctype: document.doctype ? document.doctype.name : 'html',
                        metaTags: metas,
                        forms: forms,
                        iframes: iframes,
                        shadowRoots: shadowRoots,
                        totalElementsCount: allEls.length,
                        totalLinksCount: document.querySelectorAll('a[href]').length,
                        totalScriptsCount: document.querySelectorAll('script').length,
                        totalStylesheetsCount: document.querySelectorAll('link[rel="stylesheet"]').length,
                        totalImagesCount: document.querySelectorAll('img').length,
                        headingHierarchy: headings
                    });
                } catch(e) {
                    return JSON.stringify({ error: e.toString() });
                }
            })();
        """.trimIndent()
    }

    suspend fun inspectDom(engine: ChromiumWebViewEngine?): DomSnapshot = withContext(Dispatchers.Default) {
        if (engine == null) {
            return@withContext DomSnapshot(pageUrl = "", title = "")
        }
        val currentUrl = engine.getUrl()
        val rawJson = engine.evaluateJavascriptAsync(INSPECT_DOM_JS)
        parseDomSnapshot(currentUrl, rawJson)
    }

    private fun parseDomSnapshot(currentUrl: String, rawJson: String): DomSnapshot {
        try {
            val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
            } else rawJson

            val json = JSONObject(clean)
            if (json.has("error")) {
                return DomSnapshot(pageUrl = currentUrl, title = "")
            }

            val metaList = mutableListOf<MetaTagRecord>()
            val metaArr = json.optJSONArray("metaTags") ?: JSONArray()
            for (i in 0 until metaArr.length()) {
                val o = metaArr.getJSONObject(i)
                metaList.add(MetaTagRecord(
                    name = o.optString("name", ""),
                    property = o.optString("property", ""),
                    content = o.optString("content", "")
                ))
            }

            val formList = mutableListOf<FormRecord>()
            val formArr = json.optJSONArray("forms") ?: JSONArray()
            for (i in 0 until formArr.length()) {
                val f = formArr.getJSONObject(i)
                val fieldList = mutableListOf<FormFieldRecord>()
                val fieldArr = f.optJSONArray("fields") ?: JSONArray()
                for (j in 0 until fieldArr.length()) {
                    val fl = fieldArr.getJSONObject(j)
                    val optList = mutableListOf<String>()
                    val optArr = fl.optJSONArray("options") ?: JSONArray()
                    for (k in 0 until optArr.length()) {
                        optList.add(optArr.getString(k))
                    }
                    fieldList.add(FormFieldRecord(
                        name = fl.optString("name", ""),
                        type = fl.optString("type", ""),
                        id = fl.optString("id", ""),
                        placeholder = fl.optString("placeholder", ""),
                        isRequired = fl.optBoolean("isRequired", false),
                        value = fl.optString("value", ""),
                        options = optList
                    ))
                }

                val btnList = mutableListOf<String>()
                val btnArr = f.optJSONArray("submitButtons") ?: JSONArray()
                for (j in 0 until btnArr.length()) {
                    btnList.add(btnArr.getString(j))
                }

                formList.add(FormRecord(
                    formId = f.optString("formId", ""),
                    formName = f.optString("formName", "Form ${i + 1}"),
                    action = f.optString("action", currentUrl),
                    method = f.optString("method", "GET"),
                    enctype = f.optString("enctype", ""),
                    fields = fieldList,
                    submitButtons = btnList
                ))
            }

            val iframeList = mutableListOf<FrameRecord>()
            val iframeArr = json.optJSONArray("iframes") ?: JSONArray()
            for (i in 0 until iframeArr.length()) {
                val ifr = iframeArr.getJSONObject(i)
                iframeList.add(FrameRecord(
                    frameId = ifr.optString("frameId", ""),
                    src = ifr.optString("src", ""),
                    name = ifr.optString("name", ""),
                    sandbox = ifr.optString("sandbox", ""),
                    isCrossDomain = ifr.optBoolean("isCrossDomain", false)
                ))
            }

            val shadowList = mutableListOf<ShadowDomRecord>()
            val shadowArr = json.optJSONArray("shadowRoots") ?: JSONArray()
            for (i in 0 until shadowArr.length()) {
                val s = shadowArr.getJSONObject(i)
                shadowList.add(ShadowDomRecord(
                    hostTag = s.optString("hostTag", ""),
                    hostId = s.optString("hostId", ""),
                    mode = s.optString("mode", "open"),
                    childrenCount = s.optInt("childrenCount", 0)
                ))
            }

            val headings = mutableListOf<String>()
            val headArr = json.optJSONArray("headingHierarchy") ?: JSONArray()
            for (i in 0 until headArr.length()) {
                headings.add(headArr.getString(i))
            }

            return DomSnapshot(
                pageUrl = currentUrl,
                title = json.optString("title", ""),
                charset = json.optString("charset", "UTF-8"),
                doctype = json.optString("doctype", "html"),
                metaTags = metaList,
                forms = formList,
                iframes = iframeList,
                shadowRoots = shadowList,
                totalElementsCount = json.optInt("totalElementsCount", 0),
                totalLinksCount = json.optInt("totalLinksCount", 0),
                totalScriptsCount = json.optInt("totalScriptsCount", 0),
                totalStylesheetsCount = json.optInt("totalStylesheetsCount", 0),
                totalImagesCount = json.optInt("totalImagesCount", 0),
                headingHierarchy = headings
            )
        } catch (_: Exception) {
            return DomSnapshot(pageUrl = currentUrl, title = "")
        }
    }
}
