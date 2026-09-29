package com.lichiai.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replace prompt variables in a system prompt or user message.
 *
 * Supported tokens:
 *  - {model}      → current model id
 *  - {provider}   → current provider name
 *  - {assistant}  → current assistant name
 *  - {date}       → today's date (YYYY-MM-DD, local)
 *  - {time}       → current time (HH:mm, local)
 *  - {datetime}   → ISO-ish local timestamp
 *  - {locale}     → device locale tag (e.g. en-US)
 *  - {weekday}    → Monday/Tuesday/... (locale-formatted)
 */
object PromptVars {
    fun render(
        template: String,
        model: String = "",
        provider: String = "",
        assistant: String = "",
        locale: Locale = Locale.getDefault(),
        date: Date = Date()
    ): String {
        if (template.isEmpty()) return template
        val map = mapOf(
            "model" to model,
            "provider" to provider,
            "assistant" to assistant,
            "date" to SimpleDateFormat("yyyy-MM-dd", locale).format(date),
            "time" to SimpleDateFormat("HH:mm", locale).format(date),
            "datetime" to SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(date),
            "locale" to locale.toLanguageTag(),
            "weekday" to SimpleDateFormat("EEEE", locale).format(date),
        )
        var out = template
        for ((k, v) in map) {
            out = out.replace("{$k}", v)
        }
        return out
    }
}
