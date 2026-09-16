package com.opennxt.model.files

import com.opennxt.config.ServerConfig

object ClientParams {
    const val ENV_LOCAL = 4

    val MANDATORY = setOf(5, 6, 25, 27, 35, 60)

    fun build(config: ServerConfig): Map<String, String> {
        val host = config.hostname
        val game = config.ports.game.toString()

        val out = LinkedHashMap<String, String>()

        out["25"] = ENV_LOCAL.toString()
        out["5"] = "0"
        out["6"] = "0"
        out["27"] = "5"
        out["35"] = "http://$host"
        out["60"] = "0"

        out["3"] = host
        out["37"] = host
        out["49"] = host
        out["40"] = "http://$host"

        out["2"] = "1146"
        out["38"] = "1200"

        for (p in listOf(41, 43, 45, 47)) out[p.toString()] = game
        for (p in listOf(42, 44, 46, 48)) out[p.toString()] = game

        out["1"] = "0"
        out["4"] = "0"
        out["7"] = "0"
        out["8"] = "false"
        out["11"] = "225"
        out["13"] = "false"
        out["14"] = "false"
        out["16"] = ".$host"
        out["17"] = "false"
        out["18"] = "0"
        out["20"] = "false"
        out["23"] = "false"
        out["24"] = "true"
        out["26"] = "false"
        out["28"] = "581101278"
        out["31"] = "11449"
        out["34"] = "0"
        out["39"] = "false"
        out["50"] = "0"
        out["51"] = "0"
        out["52"] = "0"
        out["57"] = "6438"

        for (p in listOf(15, 19, 22, 32, 33)) out[p.toString()] = ""

        out["21"] = "halign=true|valign=true|image=rs_logo.gif,0,-43|rotatingimage=rs3_loading_spinner.gif,0,47,9.6|progress=true,Verdana,13,0xFFFFFF,0,51"

        return out
    }
}
