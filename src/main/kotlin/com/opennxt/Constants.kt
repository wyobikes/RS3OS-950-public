package com.opennxt

import java.nio.file.Path
import java.nio.file.Paths

object Constants {
    val DATA_PATH = Paths.get("./data/")
    val CLIENTS_PATH = DATA_PATH.resolve("clients")
    val LAUNCHERS_PATH = DATA_PATH.resolve("launchers")
    val CONFIG_PATH = DATA_PATH.resolve("config")

    val CACHE_PATH: Path = System.getProperty("opennxt.cache")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: DATA_PATH.resolve("cache")
    val PROT_PATH = DATA_PATH.resolve("prot")
    val RESOURCE_PATH = DATA_PATH.resolve("resources")
    val PROXY_PATH = DATA_PATH.resolve("proxy")
    val PROXY_DUMP_PATH = PROXY_PATH.resolve("dumps")
}
