package ir.jaru.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface

/**
 * Layered VPN discovery.
 *
 * 1. Live tun / TRANSPORT_VPN  — something is actually connected
 * 2. Always-on / lockdown      — system setting (may be empty without privilege)
 * 3. VpnService intent         — apps that expose a real Android VPN service
 * 4. BIND_VPN_SERVICE on any service in the package
 * 5. Known catalog             — popular clients even if they hide the service
 * 6. Name / label heuristic    — last resort, low confidence, unchecked by default
 *
 * System packages are reported but never selected for uninstall.
 * This app never uses Accessibility or silent uninstall.
 */
class VpnScanner(private val ctx: Context) {

    private val pm = ctx.packageManager
    private val own = ctx.packageName

    fun scan(onFound: ((Hit) -> Unit)? = null): ScanReport {
        val map = LinkedHashMap<String, Hit>()
        val report = ScanReport(hits = mutableListOf())

        report.tunIfaces = tunNames()
        report.vpnAlive = hasTransportVpn() || report.tunIfaces.isNotEmpty()
        report.alwaysOnPkg = readSecure("always_on_vpn_app")
        report.lockdown = readSecure("always_on_vpn_lockdown") in setOf("1", "true")

        fun emit(hit: Hit) {
            val old = map[hit.packageName]
            if (old == null) {
                map[hit.packageName] = hit
                onFound?.invoke(hit)
            } else {
                hit.reasons.forEach { r -> if (r !in old.reasons) old.reasons += r }
                old.bump(hit.confidence)
                old.active = old.active || hit.active
                old.alwaysOn = old.alwaysOn || hit.alwaysOn
            }
        }

        // Layer 3 — VpnService
        queryVpnServices().forEach { pkg ->
            buildHit(pkg, reason = ctx.getString(R.string.reason_vpn_service), confidence = Confidence.HIGH)?.let(::emit)
        }

        // Layer 4 — BIND_VPN_SERVICE
        installed(PackageManager.GET_SERVICES).forEach { pi ->
            val pkg = pi.packageName
            if (pkg == own || pkg in VpnCatalog.skipAlways) return@forEach
            val hitVpn = pi.services?.any { it.permission == "android.permission.BIND_VPN_SERVICE" } == true
            if (hitVpn) {
                buildHit(pkg, reason = ctx.getString(R.string.reason_bind), confidence = Confidence.HIGH)?.let(::emit)
            }
        }

        // Layer 5 — catalog
        VpnCatalog.packages.keys.forEach { pkg ->
            if (isInstalled(pkg)) {
                val name = VpnCatalog.packages[pkg] ?: pkg
                buildHit(
                    pkg,
                    reason = ctx.getString(R.string.reason_catalog, name),
                    confidence = Confidence.MEDIUM
                )?.let(::emit)
            }
        }

        // Layer 6 — heuristic
        installed(0).forEach { pi ->
            val pkg = pi.packageName
            if (pkg == own || pkg in VpnCatalog.skipAlways) return@forEach
            if (map.containsKey(pkg)) return@forEach
            val label = try {
                pi.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
            } catch (_: Exception) { "" }
            val key = VpnCatalog.looksLikeVpn(pkg, label) ?: return@forEach
            buildHit(
                pkg,
                reason = ctx.getString(R.string.reason_name, key),
                confidence = Confidence.LOW
            )?.let(::emit)
        }

        // Layer 1+2 — annotate the actual always-on owner only
        report.alwaysOnPkg?.let { pkg ->
            if (pkg.isNotBlank()) {
                val existing = map[pkg]
                if (existing != null) {
                    existing.alwaysOn = true
                    existing.active = true
                    val tag = ctx.getString(R.string.reason_always_on_short)
                    if (tag !in existing.reasons) existing.reasons += tag
                    existing.bump(Confidence.HIGH)
                } else {
                    buildHit(pkg, reason = ctx.getString(R.string.reason_always_on), confidence = Confidence.HIGH)?.also {
                        it.alwaysOn = true
                        it.active = true
                        emit(it)
                    }
                }
            }
        }

        report.hits.addAll(
            map.values.sortedWith(
                compareByDescending<Hit> { it.active }
                    .thenByDescending { it.alwaysOn }
                    .thenBy { it.confidence.ordinal }
                    .thenBy { it.label.lowercase() }
            )
        )
        return report
    }

    private fun buildHit(pkg: String, reason: String, confidence: Confidence): Hit? {
        if (pkg == own || pkg in VpnCatalog.skipAlways) return null
        val pi = try {
            if (Build.VERSION.SDK_INT >= 33)
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            return null
        }
        val ai = pi.applicationInfo ?: return null
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val label = try { ai.loadLabel(pm).toString() } catch (_: Exception) { pkg }
        val conf = if (system) Confidence.SYSTEM else confidence
        val checked = !system && conf != Confidence.LOW
        return Hit(
            packageName = pkg,
            label = label,
            version = pi.versionName ?: "",
            reasons = mutableListOf(reason),
            confidence = conf,
            system = system,
            active = false,
            alwaysOn = false,
            checked = checked
        )
    }

    private fun queryVpnServices(): Set<String> {
        val intent = Intent(VpnService.SERVICE_INTERFACE)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        return list.map { it.serviceInfo.packageName }.toSet()
    }

    private fun installed(extra: Int): List<android.content.pm.PackageInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(extra.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(extra)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) { false }

    private fun hasTransportVpn(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) { false }
    }

    private fun tunNames(): List<String> {
        val out = mutableListOf<String>()
        try {
            val it = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in it) {
                val n = ni.name.lowercase()
                if (!ni.isUp) continue
                if (
                    n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") ||
                    n.startsWith("utun") || n.startsWith("tap") || n.contains("tun") ||
                    n.startsWith("ipsec") || n.startsWith("offload") && n.contains("vpn")
                ) {
                    out += ni.name
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun readSecure(key: String): String? = try {
        Settings.Secure.getString(ctx.contentResolver, key)
    } catch (_: Exception) { null }

    fun stillInstalled(pkg: String): Boolean = isInstalled(pkg)
}
