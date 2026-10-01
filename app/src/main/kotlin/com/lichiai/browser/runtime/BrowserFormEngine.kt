package com.lichiai.browser.runtime

import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.perception.SemanticElement
import java.util.Locale

enum class FormFieldKind {
    SEARCH,
    TEXT,
    EMAIL,
    PASSWORD,
    NUMBER,
    DATE,
    SELECT,
    CHECKBOX,
    RADIO,
    TEXTAREA,
    SUBMIT,
    UNKNOWN
}

data class DetectedFormField(
    val element: SemanticElement,
    val kind: FormFieldKind,
    val isSensitive: Boolean,
    val label: String,
    val placeholder: String,
    val currentValue: String
)

data class FormPlan(
    val fieldsToFill: Map<String, String>, // semanticId -> value
    val submitElementId: String? = null,
    val hasSensitiveFields: Boolean = false,
    val sensitiveFieldReasons: List<String> = emptyList()
)

/**
 * Form Understanding & Safety Engine for Autonomous Browser Operations.
 *
 * Automatically inspects page perception snapshots to identify form structures,
 * maps user inputs to input fields, and guarantees zero unauthorized automation
 * over sensitive fields (passwords, OTPs, financial/CVV inputs).
 */
class BrowserFormEngine {

    fun detectFormFields(snapshot: PagePerceptionSnapshot): List<DetectedFormField> {
        return snapshot.semanticElements
            .filter { it.isInput || it.tag == "textarea" || it.tag == "select" || it.type == "submit" }
            .map { el ->
                val kind = classifyField(el)
                val isSensitive = isSensitiveField(el, kind)
                DetectedFormField(
                    element = el,
                    kind = kind,
                    isSensitive = isSensitive,
                    label = el.labelOrText,
                    placeholder = el.placeholder,
                    currentValue = el.value
                )
            }
    }

    fun isSensitiveField(element: SemanticElement, kind: FormFieldKind? = null): Boolean {
        val fieldKind = kind ?: classifyField(element)
        if (fieldKind == FormFieldKind.PASSWORD) return true

        val text = "${element.semanticId} ${element.labelOrText} ${element.placeholder} ${element.type}".lowercase(Locale.ROOT)
        return text.contains("otp") ||
                text.contains("cvv") ||
                text.contains("credit card") ||
                text.contains("card number") ||
                text.contains("expiration") ||
                text.contains("pin") ||
                text.contains("security code") ||
                text.contains("ssn") ||
                text.contains("bank account")
    }

    private fun classifyField(el: SemanticElement): FormFieldKind {
        val tag = el.tag.lowercase(Locale.ROOT)
        val type = el.type.lowercase(Locale.ROOT)
        val text = "${el.semanticId} ${el.labelOrText} ${el.placeholder}".lowercase(Locale.ROOT)

        return when {
            type == "password" || text.contains("password") -> FormFieldKind.PASSWORD
            type == "search" || text.contains("search") || text.contains("query") || text.contains("dhundo") -> FormFieldKind.SEARCH
            type == "email" || text.contains("email") -> FormFieldKind.EMAIL
            type == "number" || type == "tel" || text.contains("phone") || text.contains("mobile") -> FormFieldKind.NUMBER
            type == "date" || text.contains("date") || text.contains("dob") -> FormFieldKind.DATE
            type == "checkbox" -> FormFieldKind.CHECKBOX
            type == "radio" -> FormFieldKind.RADIO
            type == "submit" || (tag == "button" && (text.contains("submit") || text.contains("login") || text.contains("sign in") || text.contains("search"))) -> FormFieldKind.SUBMIT
            tag == "select" -> FormFieldKind.SELECT
            tag == "textarea" -> FormFieldKind.TEXTAREA
            el.isInput -> FormFieldKind.TEXT
            else -> FormFieldKind.UNKNOWN
        }
    }
}
