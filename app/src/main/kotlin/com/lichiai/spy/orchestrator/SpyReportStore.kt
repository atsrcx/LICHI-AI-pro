package com.lichiai.spy.orchestrator

import android.content.Context
import java.io.File
import java.util.UUID

object SpyReportStore {

    fun saveReport(context: Context, htmlContent: String, customReportId: String? = null): String {
        val reportId = customReportId ?: UUID.randomUUID().toString()
        val reportsDir = File(context.filesDir, "spy_reports")
        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }
        val file = File(reportsDir, "$reportId.html")
        file.writeText(htmlContent, Charsets.UTF_8)
        return reportId
    }

    fun getReportHtml(context: Context, reportId: String): String? {
        val file = File(File(context.filesDir, "spy_reports"), "$reportId.html")
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    fun getReportFile(context: Context, reportId: String): File? {
        val file = File(File(context.filesDir, "spy_reports"), "$reportId.html")
        return if (file.exists()) file else null
    }
}
