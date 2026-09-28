package com.lichiai.browser.engine

object BrowserJsBridge {

    val EXTRACT_LINKS_JS = """
        (function() {
            try {
                // Clear any previous candidate tags
                var oldTagged = document.querySelectorAll('[data-lichi-idx]');
                for (var j = 0; j < oldTagged.length; j++) {
                    oldTagged[j].removeAttribute('data-lichi-idx');
                }

                var results = [];
                var anchors = Array.from(document.querySelectorAll('a[href]'));
                var index = 1;
                
                for (var i = 0; i < anchors.length && results.length < 15; i++) {
                    var a = anchors[i];
                    var href = a.href;
                    var text = (a.innerText || a.textContent || '').trim().replace(/\s+/g, ' ');
                    
                    if (!href || href.startsWith('javascript:') || href.startsWith('#') || text.length < 2) continue;
                    if (href.includes('google.com/search') || href.includes('accounts.google.com') || 
                        href.includes('google.com/preferences') || href.includes('support.google.com') ||
                        href.includes('google.com/intl') || href.includes('policies.google.com')) continue;
                    
                    var lowerText = text.toLowerCase();
                    var lowerHref = href.toLowerCase();
                    
                    var isOfficial = lowerText.includes('official') || lowerText.includes('official site') || 
                                     lowerText.includes('official website') || lowerText.includes('home') || 
                                     lowerHref.includes('official');
                    var isDownload = lowerText.includes('download') || lowerText.includes('get ') || 
                                     lowerText.includes('install') || lowerText.includes('apk');
                    var isPrice = lowerText.includes('₹') || lowerText.includes('$') || 
                                  lowerText.includes('rs.') || lowerText.includes('price') || lowerText.includes('rate');

                    if (!results.some(function(r) { return r.url === href; })) {
                        a.setAttribute('data-lichi-idx', index.toString());
                        
                        var parentContainer = a.closest('div.g') || a.closest('div[data-sokoban-container]') || a.parentElement;
                        var snippetText = '';
                        if (parentContainer) {
                            var parentText = (parentContainer.innerText || '').replace(text, '').trim().replace(/\s+/g, ' ');
                            snippetText = parentText.substring(0, 150);
                        }
                        if (!snippetText) {
                            snippetText = (a.title || a.getAttribute('aria-label') || '').substring(0, 120);
                        }

                        results.push({
                            index: index,
                            title: text.substring(0, 120),
                            url: href,
                            snippet: snippetText,
                            isOfficial: isOfficial,
                            isDownload: isDownload,
                            isPrice: isPrice
                        });
                        index++;
                    }
                }
                return JSON.stringify(results);
            } catch(e) {
                return "[]";
            }
        })();
    """.trimIndent()

    val EXTRACT_INTERACTIVE_ELEMENTS_JS = """
        (function() {
            try {
                // Clear old interactive tags
                var oldElTagged = document.querySelectorAll('[data-lichi-el]');
                for (var j = 0; j < oldElTagged.length; j++) {
                    oldElTagged[j].removeAttribute('data-lichi-el');
                }

                var selectors = 'a[href], button, input, textarea, select, [role="button"], [role="link"], [role="searchbox"], [role="textbox"], summary';
                var nodes = Array.from(document.querySelectorAll(selectors));
                var results = [];
                var index = 1;

                for (var i = 0; i < nodes.length && results.length < 35; i++) {
                    var el = nodes[i];
                    var rect = el.getBoundingClientRect();
                    var style = window.getComputedStyle(el);
                    
                    // Filter hidden / zero-dimension elements
                    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
                    if (rect.width === 0 && rect.height === 0) continue;

                    var tag = el.tagName.toLowerCase();
                    var type = el.getAttribute('type') || (tag === 'button' ? 'button' : '');
                    if (type.toLowerCase() === 'hidden') continue;

                    var text = (el.innerText || el.textContent || el.value || '').trim().replace(/\s+/g, ' ');
                    var placeholder = el.getAttribute('placeholder') || '';
                    var ariaLabel = el.getAttribute('aria-label') || el.getAttribute('title') || '';
                    var href = (tag === 'a') ? (el.href || '') : '';
                    var name = el.getAttribute('name') || '';
                    var id = el.id || '';
                    var value = (el.value || '').substring(0, 100);

                    // Skip empty meaningless spans or containers unless inputs
                    var isInput = (tag === 'input' || tag === 'textarea' || tag === 'select');
                    if (!isInput && text.length === 0 && ariaLabel.length === 0 && placeholder.length === 0) continue;

                    el.setAttribute('data-lichi-el', index.toString());

                    results.push({
                        index: index,
                        tag: tag,
                        type: type,
                        text: text.substring(0, 80),
                        placeholder: placeholder.substring(0, 60),
                        href: href.substring(0, 150),
                        name: name,
                        id: id,
                        ariaLabel: ariaLabel.substring(0, 60),
                        isVisible: true,
                        isClickable: !isInput || type === 'submit' || type === 'button' || type === 'checkbox' || type === 'radio',
                        isInput: isInput,
                        value: value,
                        bounds: Math.round(rect.top) + ',' + Math.round(rect.left) + ',' + Math.round(rect.width) + ',' + Math.round(rect.height)
                    });
                    index++;
                }
                return JSON.stringify(results);
            } catch(e) {
                return "[]";
            }
        })();
    """.trimIndent()

    val EXTRACT_TEXT_SNIPPET_JS = """
        (function() {
            try {
                var clone = document.body.cloneNode(true);
                var toRemove = clone.querySelectorAll('script, style, noscript, nav, footer, svg, iframe, header, [role="banner"], [role="navigation"]');
                for (var i = 0; i < toRemove.length; i++) toRemove[i].remove();
                var text = (clone.innerText || clone.textContent || '').trim().replace(/\s+/g, ' ');
                return text.substring(0, 1500);
            } catch(e) {
                return "";
            }
        })();
    """.trimIndent()

    val EXTRACT_PRICES_JS = """
        (function() {
            try {
                var text = document.body.innerText || "";
                var priceRegex = /(?:₹|Rs\.?|INR|\$)\s*[\d,]+(?:\.\d{2})?/gi;
                var matches = text.match(priceRegex) || [];
                var unique = Array.from(new Set(matches.map(function(p) { return p.trim(); })));
                return JSON.stringify(unique.slice(0, 8));
            } catch(e) {
                return "[]";
            }
        })();
    """.trimIndent()

    val EXTRACT_TABLES_JS = """
        (function() {
            try {
                var tables = Array.from(document.querySelectorAll('table'));
                var result = [];
                for (var t = 0; t < tables.length && result.length < 3; t++) {
                    var table = tables[t];
                    var caption = table.querySelector('caption');
                    var title = caption ? caption.innerText.trim() : ('Table ' + (t + 1));
                    var headers = [];
                    var ths = table.querySelectorAll('th');
                    for (var h = 0; h < ths.length; h++) {
                        headers.push(ths[h].innerText.trim().replace(/\s+/g, ' '));
                    }
                    var rows = [];
                    var trs = table.querySelectorAll('tbody tr, tr');
                    for (var r = 0; r < trs.length && rows.length < 15; r++) {
                        var tds = trs[r].querySelectorAll('td');
                        if (tds.length > 0) {
                            var rowData = [];
                            for (var d = 0; d < tds.length; d++) {
                                rowData.push(tds[d].innerText.trim().replace(/\s+/g, ' '));
                            }
                            rows.push(rowData);
                        }
                    }
                    if (rows.length > 0) {
                        result.push({ title: title, headers: headers, rows: rows });
                    }
                }
                return JSON.stringify(result);
            } catch(e) {
                return "[]";
            }
        })();
    """.trimIndent()

    fun buildHighlightElementJs(index: Int): String {
        return """
            (function() {
                try {
                    var el = document.querySelector('[data-lichi-el="$index"]') || document.querySelector('[data-lichi-idx="$index"]');
                    if (el) {
                        el.scrollIntoView({behavior: 'smooth', block: 'center'});
                        var prevOutline = el.style.outline;
                        el.style.outline = '3px solid #2563EB';
                        el.style.backgroundColor = 'rgba(37, 99, 235, 0.15)';
                        setTimeout(function() {
                            el.style.outline = prevOutline;
                            el.style.backgroundColor = '';
                        }, 2500);
                        return "HIGHLIGHTED";
                    }
                    return "NOT_FOUND";
                } catch(e) {
                    return "ERROR: " + e.message;
                }
            })();
        """.trimIndent()
    }

    val GET_PAGE_STATE_JS = """
        (function() {
            try {
                var body = document.body;
                var html = document.documentElement;
                var maxScroll = Math.max(
                    body ? body.scrollHeight : 0,
                    body ? body.offsetHeight : 0,
                    html ? html.clientHeight : 0,
                    html ? html.scrollHeight : 0,
                    html ? html.offsetHeight : 0
                ) - (window.innerHeight || 0);

                var interactiveCount = document.querySelectorAll('a[href], button, input, textarea, select').length;
                var textLen = (document.body ? (document.body.innerText || '').length : 0);

                return JSON.stringify({
                    readyState: document.readyState || 'complete',
                    scrollY: Math.round(window.scrollY || window.pageYOffset || 0),
                    maxScrollY: Math.max(0, Math.round(maxScroll)),
                    textLength: textLen,
                    interactiveCount: interactiveCount,
                    isLoaded: document.readyState === 'complete'
                });
            } catch(e) {
                return JSON.stringify({ readyState: 'complete', scrollY: 0, maxScrollY: 0, textLength: 0, interactiveCount: 0, isLoaded: true });
            }
        })();
    """.trimIndent()

    fun buildClickCandidateJs(index: Int, targetUrl: String? = null): String {
        val escapedUrl = targetUrl?.replace("'", "\\'") ?: ""
        return """
            (function() {
                try {
                    // 1. Direct tagged element lookup
                    var target = document.querySelector('[data-lichi-idx="$index"]');
                    
                    // 2. Direct href match if targetUrl provided
                    if (!target && '$escapedUrl'.length > 0) {
                        target = document.querySelector('a[href*="$escapedUrl"]');
                    }

                    // 3. Fallback: Filter anchors with same logic
                    if (!target) {
                        var anchors = Array.from(document.querySelectorAll('a[href]'));
                        var valid = [];
                        for (var i = 0; i < anchors.length; i++) {
                            var a = anchors[i];
                            var href = a.href;
                            var text = (a.innerText || a.textContent || '').trim();
                            if (href && !href.startsWith('javascript:') && !href.startsWith('#') && text.length > 1) {
                                if (!href.includes('google.com/search') && !href.includes('accounts.google.com')) {
                                    if (!valid.some(function(v) { return v.href === href; })) {
                                        valid.push(a);
                                    }
                                }
                            }
                        }
                        var targetIdx = $index - 1;
                        if (targetIdx >= 0 && targetIdx < valid.length) {
                            target = valid[targetIdx];
                        }
                    }

                    if (target) {
                        target.scrollIntoView({behavior: 'smooth', block: 'center'});
                        target.focus();
                        try {
                            target.dispatchEvent(new MouseEvent('mousedown', {bubbles: true, cancelable: true, view: window}));
                            target.dispatchEvent(new MouseEvent('mouseup', {bubbles: true, cancelable: true, view: window}));
                            target.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));
                        } catch(_e) {}
                        target.click();
                        return "CLICKED";
                    }
                    return "NOT_FOUND";
                } catch(e) {
                    return "ERROR: " + e.message;
                }
            })();
        """.trimIndent()
    }

    fun buildClickElementJs(index: Int): String {
        return """
            (function() {
                try {
                    var el = document.querySelector('[data-lichi-el="$index"]');
                    if (!el) {
                        // Fallback: check candidates index
                        el = document.querySelector('[data-lichi-idx="$index"]');
                    }
                    if (el) {
                        el.scrollIntoView({behavior: 'smooth', block: 'center'});
                        el.focus();
                        try {
                            el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true, cancelable: true, view: window}));
                            el.dispatchEvent(new MouseEvent('mousedown', {bubbles: true, cancelable: true, view: window}));
                            el.dispatchEvent(new MouseEvent('mouseup', {bubbles: true, cancelable: true, view: window}));
                            el.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));
                        } catch(_e) {}
                        el.click();
                        return "CLICKED";
                    }
                    return "NOT_FOUND";
                } catch(e) {
                    return "ERROR: " + e.message;
                }
            })();
        """.trimIndent()
    }

    fun buildClickTextJs(targetText: String): String {
        val escaped = targetText.replace("'", "\\'")
        return """
            (function() {
                try {
                    var lowerTarget = '$escaped'.toLowerCase();
                    var elements = Array.from(document.querySelectorAll('a, button, [role="button"], input[type="submit"], input[type="button"], h3, span, p'));
                    for (var i = 0; i < elements.length; i++) {
                        var el = elements[i];
                        var text = (el.innerText || el.textContent || el.value || '').toLowerCase();
                        if (text.includes(lowerTarget) && text.length < 200) {
                            var clickable = el.closest('a') || el.closest('button') || el.closest('[role="button"]') || el;
                            clickable.scrollIntoView({behavior: 'smooth', block: 'center'});
                            clickable.focus();
                            try {
                                clickable.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));
                            } catch(_e) {}
                            clickable.click();
                            return "CLICKED";
                        }
                    }
                    return "NOT_FOUND";
                } catch(e) {
                    return "ERROR: " + e.message;
                }
            })();
        """.trimIndent()
    }

    fun buildTypeTextJs(index: Int?, selector: String?, text: String, submit: Boolean): String {
        val escapedText = text.replace("'", "\\'").replace("\n", "\\n")
        val escapedSelector = selector?.replace("'", "\\'") ?: ""
        return """
            (function() {
                try {
                    var target = null;
                    if (${index != null}) {
                        target = document.querySelector('[data-lichi-el="$index"]');
                    }
                    if (!target && '$escapedSelector'.length > 0) {
                        target = document.querySelector('$escapedSelector');
                    }
                    if (!target) {
                        // Fallback: Find first visible input or textarea
                        var inputs = Array.from(document.querySelectorAll('input:not([type="hidden"]), textarea'));
                        for (var i = 0; i < inputs.length; i++) {
                            var rect = inputs[i].getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) {
                                target = inputs[i];
                                break;
                            }
                        }
                    }

                    if (target) {
                        target.scrollIntoView({behavior: 'smooth', block: 'center'});
                        target.focus();
                        target.value = '$escapedText';
                        target.dispatchEvent(new Event('input', {bubbles: true, cancelable: true}));
                        target.dispatchEvent(new Event('change', {bubbles: true, cancelable: true}));
                        
                        if ($submit) {
                            try {
                                var form = target.closest('form');
                                if (form) {
                                    form.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
                                    form.submit();
                                } else {
                                    target.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', keyCode: 13, bubbles: true}));
                                    target.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', keyCode: 13, bubbles: true}));
                                }
                            } catch(_f) {}
                        }
                        return "TYPED";
                    }
                    return "NOT_FOUND";
                } catch(e) {
                    return "ERROR: " + e.message;
                }
            })();
        """.trimIndent()
    }

    fun buildScrollJs(direction: String, amount: Int): String {
        val pixelDelta = 600 * amount
        return when (direction.uppercase()) {
            "DOWN" -> "window.scrollBy({top: $pixelDelta, behavior: 'smooth'});"
            "UP" -> "window.scrollBy({top: -$pixelDelta, behavior: 'smooth'});"
            "TOP" -> "window.scrollTo({top: 0, behavior: 'smooth'});"
            "BOTTOM" -> "window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});"
            else -> "window.scrollBy({top: $pixelDelta, behavior: 'smooth'});"
        }
    }
}


