package com.uxspace.phone

import com.uxspace.apps.InstalledApp

/**
 * Pinned productivity apps for the glasses workspace. Matches whatever is installed;
 * missing packages are simply omitted from the row.
 */
object QuickLauncher {

    data class Pin(
        val id: String,
        val label: String,
        val packages: List<String>,
    )

    val pins = listOf(
        Pin("browser", "Przeglądarka", listOf(
            "com.android.chrome",
            "com.brave.browser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
        )),
        Pin("mail", "Gmail", listOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.samsung.android.email.provider",
        )),
        Pin("ai", "ChatGPT", listOf(
            "com.openai.chatgpt",
            "com.anthropic.claude",
            "com.google.android.googlequicksearchbox",
        )),
        Pin("chat", "Slack / Teams", listOf(
            "com.Slack",
            "com.microsoft.teams",
            "com.microsoft.teams2",
            "com.discord",
        )),
        Pin("video", "YouTube", listOf(
            "com.google.android.youtube",
            "com.netflix.mediaclient",
        )),
        Pin("work", "Terminal / RDP", listOf(
            "com.termux",
            "com.microsoft.rdc.androidx",
            "com.microsoft.rdc.android",
            "com.google.android.apps.tachyon",
        )),
    )

    fun resolve(apps: List<InstalledApp>): List<Pair<Pin, InstalledApp>> {
        val byPackage = apps.associateBy { it.packageName }
        return pins.mapNotNull { pin ->
            val match = pin.packages.firstNotNullOfOrNull { byPackage[it] } ?: return@mapNotNull null
            pin to match
        }
    }
}
