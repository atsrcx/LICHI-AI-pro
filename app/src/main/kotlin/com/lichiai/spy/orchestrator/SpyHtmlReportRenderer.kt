package com.lichiai.spy.orchestrator

import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.spy.model.ProfileConflict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProviderExecutionStats(
    val providerId: String,
    val providerName: String,
    val status: String, // "SUCCESS" | "FAILED" | "SKIPPED"
    val latencyMs: Long,
    val recordCount: Int,
    val error: String? = null
)

object SpyHtmlReportRenderer {

    fun renderHtml(
        task: SpyTask,
        profile: PlatformProfile,
        providerStats: List<ProviderExecutionStats>,
        conflicts: List<ProfileConflict> = profile.conflicts
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val generatedAt = dateFormat.format(Date())

        val successfulCount = providerStats.count { it.status == "SUCCESS" }
        val failedCount = providerStats.count { it.status == "FAILED" }
        val totalExecuted = providerStats.size

        return buildString {
            append("""
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Platform Intelligence Report — @${escape(profile.username)}</title>
    <style>
        :root {
            --bg: #0d1117;
            --surface: #161b22;
            --surface-hover: #1f242c;
            --border: #30363d;
            --text-primary: #f0f6fc;
            --text-secondary: #8b949e;
            --brand: #8a2be2;
            --brand-light: #a855f7;
            --success: #2ea043;
            --warning: #d29922;
            --danger: #f85149;
            --card-radius: 12px;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background-color: var(--bg);
            color: var(--text-primary);
            line-height: 1.5;
            padding: 20px;
        }
        .container { max-width: 900px; margin: 0 auto; }
        .header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding-bottom: 16px;
            border-bottom: 1px solid var(--border);
            margin-bottom: 24px;
        }
        .header-title { font-size: 20px; font-weight: 700; color: var(--brand-light); display: flex; align-items: center; gap: 8px; }
        .header-badge {
            background: rgba(138, 43, 226, 0.2);
            color: var(--brand-light);
            padding: 4px 10px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: 600;
        }
        .card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: var(--card-radius);
            padding: 20px;
            margin-bottom: 20px;
        }
        .card-title {
            font-size: 16px;
            font-weight: 600;
            margin-bottom: 16px;
            color: var(--text-primary);
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .profile-row {
            display: flex;
            align-items: center;
            gap: 20px;
        }
        .avatar {
            width: 80px;
            height: 80px;
            border-radius: 50%;
            object-fit: cover;
            border: 2px solid var(--brand);
            background: var(--surface-hover);
        }
        .profile-info h1 { font-size: 22px; font-weight: 700; margin-bottom: 4px; }
        .profile-info .username { color: var(--brand-light); font-size: 15px; margin-bottom: 8px; }
        .profile-info .bio { color: var(--text-secondary); font-size: 14px; margin-top: 6px; }
        .metrics-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
            gap: 12px;
            margin-top: 14px;
        }
        .metric-box {
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 12px;
            text-align: center;
        }
        .metric-value { font-size: 18px; font-weight: 700; color: var(--text-primary); }
        .metric-label { font-size: 12px; color: var(--text-secondary); text-transform: uppercase; margin-top: 2px; }
        .table {
            width: 100%;
            border-collapse: collapse;
            font-size: 13px;
        }
        .table th, .table td {
            padding: 10px 12px;
            text-align: left;
            border-bottom: 1px solid var(--border);
        }
        .table th { color: var(--text-secondary); font-weight: 600; background: rgba(255, 255, 255, 0.02); }
        .status-badge {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 11px;
            font-weight: 600;
        }
        .status-success { background: rgba(46, 160, 67, 0.2); color: var(--success); }
        .status-failed { background: rgba(248, 81, 73, 0.2); color: var(--danger); }
        .media-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
            gap: 12px;
        }
        .media-card {
            background: var(--surface-hover);
            border-radius: 8px;
            overflow: hidden;
            border: 1px solid var(--border);
        }
        .media-thumb {
            width: 100%;
            height: 120px;
            object-fit: cover;
        }
        .media-caption {
            padding: 8px;
            font-size: 11px;
            color: var(--text-secondary);
            max-height: 40px;
            overflow: hidden;
        }
        .conflict-box {
            background: rgba(210, 153, 34, 0.1);
            border: 1px solid rgba(210, 153, 34, 0.4);
            border-radius: 8px;
            padding: 12px;
            margin-bottom: 12px;
        }
        .conflict-title { font-weight: 600; color: var(--warning); font-size: 13px; margin-bottom: 4px; }
        .footer {
            text-align: center;
            color: var(--text-secondary);
            font-size: 12px;
            margin-top: 30px;
            padding-top: 16px;
            border-top: 1px solid var(--border);
        }
    </style>
</head>
<body>
<div class="container">
    <div class="header">
        <div class="header-title">
            <span>🛡️</span> LICHI Platform Intelligence
        </div>
        <div class="header-badge">FULL Aggregation Mode</div>
    </div>

    <!-- Profile Overview Card -->
    <div class="card">
        <div class="profile-row">
            ${if (profile.avatarUrl.isNotBlank()) "<img class=\"avatar\" src=\"${escape(profile.avatarUrl)}\" alt=\"Avatar\" onerror=\"this.style.display='none'\"/>" else "<div class=\"avatar\" style=\"display:flex;align-items:center;justify-content:center;font-size:24px;\">👤</div>"}
            <div class="profile-info">
                <h1>${escape(profile.displayName.ifBlank { profile.username })}</h1>
                <div class="username">@${escape(profile.username)} • ${escape(task.platform.displayName)}</div>
                ${if (profile.bio.isNotBlank()) "<div class=\"bio\">${escape(profile.bio)}</div>" else ""}
                ${if (profile.website.isNotBlank()) "<div style=\"margin-top:6px;\"><a href=\"${escape(profile.website)}\" target=\"_blank\" style=\"color:var(--brand-light);text-decoration:none;font-size:13px;\">🔗 ${escape(profile.website)}</a></div>" else ""}
            </div>
        </div>

        <div class="metrics-grid">
            ${if (profile.followers.isNotBlank()) "<div class=\"metric-box\"><div class=\"metric-value\">${escape(profile.followers)}</div><div class=\"metric-label\">Followers</div></div>" else ""}
            ${if (profile.following.isNotBlank()) "<div class=\"metric-box\"><div class=\"metric-value\">${escape(profile.following)}</div><div class=\"metric-label\">Following</div></div>" else ""}
            ${if (profile.postCount.isNotBlank()) "<div class=\"metric-box\"><div class=\"metric-value\">${escape(profile.postCount)}</div><div class=\"metric-label\">Posts / Media</div></div>" else ""}
            ${if (profile.subscriberCount.isNotBlank()) "<div class=\"metric-box\"><div class=\"metric-value\">${escape(profile.subscriberCount)}</div><div class=\"metric-label\">Subscribers</div></div>" else ""}
            ${if (profile.views.isNotBlank()) "<div class=\"metric-box\"><div class=\"metric-value\">${escape(profile.views)}</div><div class=\"metric-label\">Views</div></div>" else ""}
            <div class="metric-box">
                <div class="metric-value">${if (profile.isVerified == true) "Verified" else if (profile.isPrivate == true) "Private" else "Public"}</div>
                <div class="metric-label">Account Type</div>
            </div>
        </div>
    </div>

    <!-- Public Contacts -->
    ${if (profile.publicEmail.isNotBlank() || profile.publicPhone.isNotBlank()) """
    <div class="card">
        <div class="card-title">💼 Public Business Contacts</div>
        <table class="table">
            <thead><tr><th>Channel</th><th>Verified Value</th><th>Status</th></tr></thead>
            <tbody>
                ${if (profile.publicEmail.isNotBlank()) "<tr><td>Email</td><td><code>${escape(profile.publicEmail)}</code></td><td><span class=\"status-badge status-success\">Publicly Listed</span></td></tr>" else ""}
                ${if (profile.publicPhone.isNotBlank()) "<tr><td>Phone</td><td><code>${escape(profile.publicPhone)}</code></td><td><span class=\"status-badge status-success\">Publicly Listed</span></td></tr>" else ""}
            </tbody>
        </table>
    </div>
    """ else ""}

    <!-- Conflicting Observations -->
    ${if (conflicts.isNotEmpty()) """
    <div class="card">
        <div class="card-title">⚠️ Conflicting Observations Detected</div>
        ${conflicts.joinToString("\n") { c ->
            """
            <div class="conflict-box">
                <div class="conflict-title">Field: ${escape(c.fieldName)}</div>
                <div style="font-size:12px;color:var(--text-secondary);margin-bottom:6px;">${escape(c.description)}</div>
                <table class="table" style="font-size:12px;">
                    <thead><tr><th>Provider</th><th>Observed Value</th></tr></thead>
                    <tbody>
                        ${c.evidences.joinToString("") { ev ->
                            "<tr><td>${escape(ev.providerId)}</td><td><b>${escape(ev.value)}</b></td></tr>"
                        }}
                    </tbody>
                </table>
            </div>
            """
        }}
    </div>
    """ else ""}

    <!-- Recent Media / Posts -->
    ${if (profile.recentMedia.isNotEmpty()) """
    <div class="card">
        <div class="card-title">📸 Public Media & Content (${profile.recentMedia.size})</div>
        <div class="media-grid">
            ${profile.recentMedia.take(12).joinToString("") { m ->
                """
                <div class="media-card">
                    ${if (m.thumbnailUrl.isNotBlank()) "<img class=\"media-thumb\" src=\"${escape(m.thumbnailUrl)}\" onerror=\"this.style.display='none'\"/>" else ""}
                    <div class="media-caption">${escape(m.caption.ifBlank { "Media item" })}</div>
                </div>
                """
            }}
        </div>
    </div>
    """ else ""}

    <!-- Providers Execution Status & Provenance -->
    <div class="card">
        <div class="card-title">⚙️ Provider Execution Audit & Provenance</div>
        <div style="font-size:13px;color:var(--text-secondary);margin-bottom:12px;">
            Executed <b>$totalExecuted</b> providers ($successfulCount succeeded, $failedCount failed).
        </div>
        <table class="table">
            <thead>
                <tr>
                    <th>Provider / Actor</th>
                    <th>Status</th>
                    <th>Latency</th>
                    <th>Records</th>
                    <th>Notes</th>
                </tr>
            </thead>
            <tbody>
                ${providerStats.joinToString("") { s ->
                    val badgeClass = if (s.status == "SUCCESS") "status-success" else "status-failed"
                    """
                    <tr>
                        <td><b>${escape(s.providerName.ifBlank { s.providerId })}</b><br><small style="color:var(--text-secondary)">${escape(s.providerId)}</small></td>
                        <td><span class="status-badge $badgeClass">${escape(s.status)}</span></td>
                        <td>${s.latencyMs} ms</td>
                        <td>${s.recordCount}</td>
                        <td style="color:var(--text-secondary);font-size:11px;">${escape(s.error ?: "Verified")}</td>
                    </tr>
                    """
                }}
            </tbody>
        </table>
    </div>

    <div class="footer">
        Generated by LICHI AI Platform Intelligence System • $generatedAt
    </div>
</div>
</body>
</html>
            """.trimIndent())
        }
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
