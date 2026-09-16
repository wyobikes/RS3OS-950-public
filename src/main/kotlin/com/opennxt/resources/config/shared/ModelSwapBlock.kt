package com.opennxt.resources.config.shared

import com.opennxt.ext.getSmartInt
import com.opennxt.ext.skip
import java.nio.ByteBuffer

object ModelSwapBlock {
    fun ByteBuffer.readModelSwapBlock(build950: Boolean): Int {
        val length = short.toInt() and 0xffff
        if (build950) skip(1)
        skip(2); skip(2)
        val lists = get().toInt() and 0xff
        if ((lists and 1) != 0) list(extras = true, pairs = false)
        if ((lists and 2) != 0) list(extras = false, pairs = false)
        if ((lists and 4) != 0) list(extras = false, pairs = true)
        if ((lists and 8) != 0) list(extras = false, pairs = false)
        skip(2)
        return length
    }

    private fun ByteBuffer.list(extras: Boolean, pairs: Boolean) {
        repeat(get().toInt() and 0xff) {
            skip(1)
            repeat(get().toInt() and 0xff) {
                skip(2); skip(2)
                getSmartInt()
                if (pairs) getSmartInt()
                if (extras) repeat(get().toInt() and 0xff) { getSmartInt() }
            }
        }
    }
}
