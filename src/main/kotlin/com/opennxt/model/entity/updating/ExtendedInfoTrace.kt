package com.opennxt.model.entity.updating

object ExtendedInfoTrace {
    data class Entry(
        val kind: String,
        val index: Int,
        val blocks: List<String>,
        val bytes: Int
    ) {
        override fun toString() = "$kind[$index]=${blocks.joinToString(",")}/${bytes}B"
    }

    data class Report(val kind: String, val viewer: String, val entries: List<Entry>, val bytes: Int) {
        fun isEmpty(): Boolean = entries.isEmpty()

        fun blockTypes(): List<String> = entries.flatMap { it.blocks }.distinct()

        override fun toString() =
            "${entries.size} ${if (kind == "npc") "npc" else "player"}(s), $bytes byte(s): " +
                entries.joinToString(" ")
    }

    private val counts = LinkedHashMap<String, Int>()
    private val sendsWithExtended = LinkedHashMap<String, Int>()
    private val bytesTotal = LinkedHashMap<String, Int>()
    private val lastReports = LinkedHashMap<String, Report>()

    @Synchronized
    fun record(report: Report) {
        totalSends[report.kind] = (totalSends[report.kind] ?: 0) + 1
        if (report.isEmpty()) return
        sendsWithExtended[report.kind] = (sendsWithExtended[report.kind] ?: 0) + 1
        bytesTotal[report.kind] = (bytesTotal[report.kind] ?: 0) + report.bytes
        for (entry in report.entries) {
            for (block in entry.blocks) {
                val key = "${report.kind}.$block"
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        lastReports[report.kind] = report
    }

    private val totalSends = LinkedHashMap<String, Int>()

    @Synchronized
    fun count(kind: String, block: String): Int = counts["$kind.$block"] ?: 0

    @Synchronized
    fun counts(): Map<String, Int> = LinkedHashMap(counts)

    @Synchronized
    fun sendsWithExtended(kind: String): Int = sendsWithExtended[kind] ?: 0

    @Synchronized
    fun totalSends(kind: String): Int = totalSends[kind] ?: 0

    @Synchronized
    fun extendedBytes(kind: String): Int = bytesTotal[kind] ?: 0

    @Synchronized
    fun lastReport(kind: String): Report? = lastReports[kind]

    @Synchronized
    fun reset() {
        counts.clear()
        sendsWithExtended.clear()
        bytesTotal.clear()
        lastReports.clear()
        totalSends.clear()
    }

    const val NPC = "npc"
    const val PLAYER = "player"
}
