package com.uxspace.hub

data class HubApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val type: String = "app",
    val url: String = "",
    val hostId: String = "",
)

data class HubDesktop(
    val id: String,
    val name: String,
    val layout: String,
    val screens: List<List<HubApp>>,
)

data class HubCommand(
    val id: String,
    val type: String,
    val desktopId: String? = null,
    val layout: String? = null,
    val packageName: String? = null,
    val activityName: String? = null,
    val label: String? = null,
    val screenIdx: Int? = null,
    val url: String? = null,
    val hostId: String? = null,
)
