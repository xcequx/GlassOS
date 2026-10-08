package com.uxspace.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Is the phone inside the tailnet right now? GlassOS does not embed Tailscale (Android
 * allows one VPN, and the official app owns it) — it reads the state and hands the
 * user to the Tailscale app for login.
 *
 * Detection is the CGNAT address every Tailscale node gets: 100.64.0.0/10 on the
 * VPN interface. No permission needed.
 */
object TailscaleStatus {

    const val PACKAGE = "com.tailscale.ipn"

    data class Snapshot(val installed: Boolean, val ip: String?) {
        val connected: Boolean get() = ip != null
        val hint: String
            get() = when {
                !installed -> "zainstaluj aplikację Tailscale"
                ip == null -> "wyłączony — zaloguj / włącz VPN"
                else -> "połączony · $ip"
            }
    }

    fun probe(context: Context): Snapshot {
        val installed = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp }
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<Inet4Address>()
                ?.map { it.hostAddress ?: "" }
                ?.firstOrNull { isCgnat(it) }
        }.getOrNull()
        return Snapshot(installed, ip)
    }

    /** 100.64.0.0/10 → first octet 100, second 64..127. */
    private fun isCgnat(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    /** Open the Tailscale app (login / toggle live there), or its store page when missing. */
    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        val intent = launch
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
