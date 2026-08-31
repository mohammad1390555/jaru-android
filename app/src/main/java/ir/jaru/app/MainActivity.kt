package ir.jaru.app

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var banner: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnPurge: MaterialButton
    private lateinit var btnVpnSettings: MaterialButton
    private lateinit var overlay: MaterialCardView
    private lateinit var overlayIcon: ImageView
    private lateinit var overlayTitle: TextView
    private lateinit var overlayMeta: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var overlayCount: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var scanner: VpnScanner
    private var report = ScanReport(mutableListOf())
    private val adapter = HitAdapter()

    private var queue = mutableListOf<Hit>()
    private var qIndex = -1
    private var waitingUninstall = false
    private var waitingPkg: String? = null
    private var purging = false

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onUninstallReturned() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanner = VpnScanner(this)

        toolbar = findViewById(R.id.toolbar)
        banner = findViewById(R.id.banner)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyHint = findViewById(R.id.emptyHint)
        btnScan = findViewById(R.id.btnScan)
        btnPurge = findViewById(R.id.btnPurge)
        btnVpnSettings = findViewById(R.id.btnVpnSettings)
        overlay = findViewById(R.id.overlay)
        overlayIcon = findViewById(R.id.overlayIcon)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlayMeta = findViewById(R.id.overlayMeta)
        overlayProgress = findViewById(R.id.overlayProgress)
        overlayCount = findViewById(R.id.overlayCount)

        setSupportActionBar(toolbar)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnScan.setOnClickListener { startScan() }
        btnPurge.setOnClickListener { confirmPurge() }
        btnVpnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        showEmpty(
            "اسکن VPN",
            "برنامه‌های تونل، پروفایل Always-on و اینترفیس tun را پیدا می‌کند.\nتیک را از هر کدام که لازم داری بردار، بقیه دانه‌دانه حذف می‌شوند."
        )
        startScan()
    }

    override fun onResume() {
        super.onResume()
        if (waitingUninstall) {
            ui.postDelayed({ onUninstallReturned() }, 700)
        }
    }

    private fun startScan() {
        if (purging) return
        report = ScanReport(mutableListOf())
        adapter.notifyDataSetChanged()
        overlay.visibility = View.GONE
        btnPurge.isEnabled = false
        btnScan.isEnabled = false
        banner.text = "در حال اسکن لایه‌ای…"
        showEmpty("داره می‌گرده", "سرویس VPN، کاتالوگ، نام بسته‌ها، tun فعال.")
        io.execute {
            val r = scanner.scan { hit ->
                ui.post {
                    report.hits += hit
                    hideEmpty()
                    adapter.notifyItemInserted(report.hits.lastIndex)
                    list.scrollToPosition(report.hits.lastIndex)
                    banner.text = "پیدا شد: ${hit.label}"
                }
                try { Thread.sleep(90) } catch (_: InterruptedException) { }
            }
            ui.post {
                report = r
                adapter.notifyDataSetChanged()
                btnScan.isEnabled = true
                refreshBanner()
                if (report.hits.isEmpty()) {
                    showEmpty(
                        if (report.vpnAlive) "تونل هست، برنامه پیدا نشد" else "VPN نصب‌شده‌ای نیست",
                        buildString {
                            if (report.vpnAlive) append("اینترفیس: ${report.tunIfaces.joinToString()}\n")
                            append("پروفایل‌های Legacy داخل تنظیمات سیستم را از دکمه پایین باز کن.")
                        }
                    )
                } else {
                    hideEmpty()
                }
            }
        }
    }

    private fun refreshBanner() {
        val n = report.hits.size
        val sel = report.hits.count { it.checked && !it.system }
        val bits = mutableListOf<String>()
        bits += "$n مورد"
        bits += "$sel انتخاب‌شده"
        if (report.vpnAlive) bits += "تونل فعال" + if (report.tunIfaces.isNotEmpty()) " (${report.tunIfaces.joinToString()})" else ""
        if (report.alwaysOnPkg != null) bits += "Always-on"
        if (report.lockdown) bits += "Lockdown"
        banner.text = bits.joinToString("  ·  ")
        btnPurge.isEnabled = sel > 0 && !purging
    }

    private fun confirmPurge() {
        val selected = report.hits.filter { it.checked && !it.system && it.state != RowState.REMOVED }
        if (selected.isEmpty()) {
            toast("چیزی تیک نخورده")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف ${selected.size} برنامه")
            .setMessage(
                "اندروید اجازهٔ حذف بی‌صدا نمی‌دهد. برای هر مورد صفحهٔ سیستم می‌آید؛ تأیید کن تا برود.\n\n" +
                    "تیک هر کدام را که لازم داری از لیست بردار، بعد ادامه بده."
            )
            .setPositiveButton("شروع حذف") { _, _ -> beginPurge(selected) }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun beginPurge(selected: List<Hit>) {
        purging = true
        waitingUninstall = false
        queue = selected.toMutableList()
        qIndex = -1
        btnPurge.isEnabled = false
        btnScan.isEnabled = false
        overlay.visibility = View.VISIBLE
        overlayProgress.max = queue.size
        overlayProgress.progress = 0
        advance()
    }

    private fun advance() {
        qIndex++
        if (qIndex >= queue.size) {
            finishPurge()
            return
        }
        val hit = queue[qIndex]
        hit.state = RowState.WAITING
        adapter.notifyDataSetChanged()
        overlayTitle.text = hit.label
        overlayMeta.text = hit.packageName
        overlayCount.text = "${qIndex + 1} از ${queue.size}"
        overlayProgress.progress = qIndex
        overlayIcon.setImageDrawable(iconOf(hit.packageName))
        if (!scanner.stillInstalled(hit.packageName)) {
            hit.state = RowState.REMOVED
            hit.checked = false
            advance()
            return
        }
        waitingUninstall = true
        waitingPkg = hit.packageName
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${hit.packageName}")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        try {
            uninstallLauncher.launch(intent)
        } catch (_: Exception) {
            hit.state = RowState.FAILED
            waitingUninstall = false
            waitingPkg = null
            advance()
        }
    }

    private fun onUninstallReturned() {
        if (!waitingUninstall) return
        if (qIndex !in queue.indices) return
        val hit = queue[qIndex]
        if (waitingPkg != null && hit.packageName != waitingPkg) return
        waitingUninstall = false
        waitingPkg = null
        val gone = !scanner.stillInstalled(hit.packageName)
        hit.state = if (gone) RowState.REMOVED else RowState.SKIPPED
        if (gone) hit.checked = false
        adapter.notifyDataSetChanged()
        overlayProgress.progress = qIndex + 1
        ui.postDelayed({ advance() }, 250)
    }

    private fun finishPurge() {
        purging = false
        waitingUninstall = false
        overlay.visibility = View.GONE
        btnScan.isEnabled = true
        val gone = queue.count { it.state == RowState.REMOVED }
        val skip = queue.count { it.state == RowState.SKIPPED }
        val fail = queue.count { it.state == RowState.FAILED }
        refreshBanner()
        MaterialAlertDialogBuilder(this)
            .setTitle("تمام")
            .setMessage("حذف شد: $gone\nرد شد: $skip\nخطا: $fail\n\nاگر تونل هنوز بالاست، از تنظیمات VPN سیستم قطعش کن.")
            .setPositiveButton("اسکن دوباره") { _, _ -> startScan() }
            .setNegativeButton("باشه", null)
            .show()
        btnPurge.isEnabled = report.hits.any { it.checked && !it.system }
    }

    private fun iconOf(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        getDrawable(R.drawable.ic_shield)
    }

    private fun showEmpty(title: String, hint: String) {
        empty.visibility = View.VISIBLE
        list.visibility = View.GONE
        emptyTitle.text = title
        emptyHint.text = hint
    }

    private fun hideEmpty() {
        empty.visibility = View.GONE
        list.visibility = View.VISIBLE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    inner class HitAdapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, v: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_hit, p, false))

        override fun getItemCount() = report.hits.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val item = report.hits[i]
            h.title.text = item.label
            h.meta.text = buildString {
                append(item.packageName)
                if (item.version.isNotBlank()) append("  ·  ").append(item.version)
            }
            h.why.text = item.reasons.joinToString("  ·  ")
            h.icon.setImageDrawable(iconOf(item.packageName))
            h.badge.text = when {
                item.system -> "سیستم"
                item.alwaysOn -> "Always-on"
                item.active -> "فعال"
                item.confidence == Confidence.HIGH -> "VPN"
                item.confidence == Confidence.MEDIUM -> "کاتالوگ"
                else -> "مشکوک"
            }
            h.badge.setBackgroundColor(
                when {
                    item.system -> 0xFF475569.toInt()
                    item.active || item.alwaysOn -> 0xFFDC2626.toInt()
                    item.confidence == Confidence.HIGH -> 0xFF059669.toInt()
                    item.confidence == Confidence.MEDIUM -> 0xFF2563EB.toInt()
                    else -> 0xFFD97706.toInt()
                }
            )
            h.status.visibility = if (item.state == RowState.IDLE) View.GONE else View.VISIBLE
            h.status.text = when (item.state) {
                RowState.WAITING -> "…"
                RowState.REMOVED -> "حذف شد"
                RowState.SKIPPED -> "رد شد"
                RowState.FAILED -> "خطا"
                RowState.BLOCKED -> "قفل"
                RowState.IDLE -> ""
            }
            val canCheck = !item.system && item.state != RowState.REMOVED
            h.check.isEnabled = canCheck && !purging
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = item.checked && canCheck
            h.check.setOnCheckedChangeListener { _, on ->
                item.checked = on
                refreshBanner()
            }
            h.itemView.setOnClickListener {
                if (canCheck && !purging) {
                    item.checked = !item.checked
                    h.check.isChecked = item.checked
                    refreshBanner()
                }
            }
            h.itemView.alpha = if (item.state == RowState.REMOVED) 0.45f else 1f
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val why: TextView = v.findViewById(R.id.why)
        val badge: TextView = v.findViewById(R.id.badge)
        val check: CheckBox = v.findViewById(R.id.check)
        val status: TextView = v.findViewById(R.id.status)
    }
}
