package ir.jaru.app

enum class Confidence { HIGH, MEDIUM, LOW, SYSTEM }

enum class RowState { IDLE, WAITING, REMOVED, SKIPPED, FAILED, BLOCKED }

data class Hit(
    val packageName: String,
    val label: String,
    val version: String,
    val reasons: MutableList<String>,
    val confidence: Confidence,
    val system: Boolean,
    val active: Boolean,
    val alwaysOn: Boolean,
    val checked: Boolean,
    val state: RowState = RowState.IDLE
) {
    fun bump(c: Confidence) {
        confidence = max(confidence, c)
    }

    private fun max(a: Confidence, b: Confidence): Confidence {
        val order = listOf(Confidence.LOW, Confidence.MEDIUM, Confidence.HIGH, Confidence.SYSTEM)
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }
}

data class ScanReport(
    val hits: MutableList<Hit>,
    val vpnAlive: Boolean = false,
    val tunIfaces: List<String> = emptyList(),
    val alwaysOnPkg: String? = null,
    val lockdown: Boolean = false
)
