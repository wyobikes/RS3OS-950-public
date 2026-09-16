package com.opennxt.config

object Membership {
    const val PROPERTY = "opennxt.members"

    fun enabled(): Boolean = when (System.getProperty(PROPERTY)?.trim()?.lowercase()) {
        "off", "false", "0", "no" -> false
        else -> true
    }

    fun wireFlag(): Int = if (enabled()) 1 else 0
}
