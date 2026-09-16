package com.opennxt.net.game.pipeline

import io.netty.buffer.ByteBuf

data class OpcodeWithBuffer(val opcode: Int, val buf: ByteBuf)
