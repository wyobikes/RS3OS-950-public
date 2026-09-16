package com.opennxt.net.game.serverprot

data class InvEntry(val obj: Int, val amount: Int, val params: List<InvParam> = emptyList()) {
    init {
        require(obj in 0..0xfffffe) { "obj id out of the 3-byte wire range (objId + 1 must fit umedium): $obj" }
        require(params.size in 0..0xff) { "a slot cannot carry more than 255 params: ${params.size}" }
    }
}

data class InvParam(val key: Int, val value: Int) {
    init {
        require(key in 0..0xffff) { "param key does not fit a ushort: $key" }
    }
}
