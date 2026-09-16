package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import mu.KotlinLogging

object Searchables {
    private val logger = KotlinLogging.logger { }

    @Volatile
    var messageSender: (com.opennxt.content.ContentPlayer, String) -> Unit = { _, _ -> }

    fun onSearch(ctx: LocContext): Any? {
        messageSender(ctx.player, "You find nothing of interest.")
        return "searched"
    }

    fun install(): Int {
        val count = ContentRegistry.onLocAction("Search", ::onSearch)
        logger.info { "searchables: bound Search across $count locs" }
        return count
    }
}
