package com.lichiai.browser.inspection.dom

import kotlinx.serialization.Serializable

@Serializable
data class FormFieldRecord(
    val name: String,
    val type: String,
    val id: String = "",
    val placeholder: String = "",
    val isRequired: Boolean = false,
    val value: String = "",
    val options: List<String> = emptyList()
)

@Serializable
data class FormRecord(
    val formId: String,
    val formName: String,
    val action: String,
    val method: String,
    val enctype: String = "",
    val fields: List<FormFieldRecord> = emptyList(),
    val submitButtons: List<String> = emptyList()
)

@Serializable
data class FrameRecord(
    val frameId: String,
    val src: String,
    val name: String = "",
    val sandbox: String = "",
    val isCrossDomain: Boolean = false
)

@Serializable
data class ShadowDomRecord(
    val hostTag: String,
    val hostId: String,
    val mode: String,
    val childrenCount: Int
)

@Serializable
data class MetaTagRecord(
    val name: String,
    val property: String,
    val content: String
)

@Serializable
data class DomSnapshot(
    val pageUrl: String,
    val title: String,
    val charset: String = "UTF-8",
    val doctype: String = "html",
    val metaTags: List<MetaTagRecord> = emptyList(),
    val forms: List<FormRecord> = emptyList(),
    val iframes: List<FrameRecord> = emptyList(),
    val shadowRoots: List<ShadowDomRecord> = emptyList(),
    val totalElementsCount: Int = 0,
    val totalLinksCount: Int = 0,
    val totalScriptsCount: Int = 0,
    val totalStylesheetsCount: Int = 0,
    val totalImagesCount: Int = 0,
    val headingHierarchy: List<String> = emptyList()
)
