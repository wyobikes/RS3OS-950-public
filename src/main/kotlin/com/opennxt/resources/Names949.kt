package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

object Names949 {
    private val logger = KotlinLogging.logger { }

    private val byId: Map<Int, String> by lazy { load() }

    private fun load(): Map<Int, String> {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("interface_names_949.sym").toFile()
        if (!file.isFile) {
            logger.info { "Names949: no ${file.path} - interface ids will print as bare numbers" }
            return emptyMap()
        }
        val out = HashMap<Int, String>()
        try {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val id = line.substring(0, tab).trim().toIntOrNull() ?: return@forEachLine
                val name = line.substring(tab + 1).trim()
                if (name.isNotEmpty()) out[id] = name
            }
        } catch (e: Exception) {
            logger.warn(e) { "Names949: could not read ${file.path}; ids will print as bare numbers" }
            return emptyMap()
        }
        logger.info { "Names949: ${out.size} build-949 interface names loaded for logging" }
        return out
    }

    fun interfaceName(id: Int): String? = byId[id]

    fun iface(id: Int): String {
        val name = byId[id] ?: return id.toString()
        return "$id ($name)"
    }

    fun component(id: Int, component: Int): String {
        val name = byId[id] ?: return "$id:$component"
        return "$id:$component ($name)"
    }

    fun hash(hash: Int): String = component((hash shr 16) and 0xffff, hash and 0xffff)

    fun size(): Int = byId.size
}
