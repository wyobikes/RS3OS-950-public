package com.opennxt.resources.defaults

import io.netty.buffer.ByteBuf

interface Default {
    val group: DefaultGroup

    fun decode(buf: ByteBuf)

}
