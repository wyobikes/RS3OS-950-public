package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

object TopLevel949 {
    private val logger = KotlinLogging.logger { }

    const val TOPLEVEL = 1477

    data class Window(
        val mount: Int,
        val struct: Int,
        val name: String,
        val layers: List<Int>,
        val content: List<Int>,
    )

    private val windows: List<Window> by lazy { load() }

    private fun load(): List<Window> {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("toplevel_949.tsv").toFile()
        if (!file.isFile) {
            logger.info {
                "TopLevel949: ${file.path} not found; using built-in tables"
            }
            return emptyList()
        }
        val out = ArrayList<Window>()
        var malformed = 0
        try {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("mount\t")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 3) { malformed++; return@forEachLine }
                val mount = p[0].toIntOrNull()
                val struct = p[1].toIntOrNull()
                if (mount == null || struct == null) { malformed++; return@forEachLine }
                fun ints(s: String?) =
                    s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
                out.add(Window(mount, struct, p[2], ints(p.getOrNull(3)), ints(p.getOrNull(4))))
            }
        } catch (e: Exception) {
            logger.warn(e) { "TopLevel949: could not read ${file.path}; using built-in tables" }
            return emptyList()
        }
        if (malformed > 0) logger.warn { "TopLevel949: $malformed malformed line(s) skipped in ${file.name}" }
        logger.info {
            "TopLevel949: ${out.size} windows loaded " +
                "(${out.count { it.content.isNotEmpty() }} with content, " +
                "${out.sumOf { it.layers.size }} layer components)"
        }
        return out
    }

    fun all(): List<Window> = windows

    fun byMount(component: Int): Window? = windows.firstOrNull { it.mount == component }

    fun byName(name: String): List<Window> = windows.filter { it.name.equals(name, ignoreCase = true) }

    fun owning(component: Int): Window? = windows.firstOrNull { component in it.layers }

    fun hosting(interfaceId: Int): List<Window> = windows.filter { interfaceId in it.content }

    fun describe(component: Int): String {
        val w = owning(component) ?: return "$TOPLEVEL:$component"
        val which = if (w.mount == component) "" else " layer of"
        return "$TOPLEVEL:$component$which '${w.name}'"
    }

    fun size(): Int = windows.size
}
