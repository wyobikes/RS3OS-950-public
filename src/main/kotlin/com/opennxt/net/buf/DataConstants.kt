package com.opennxt.net.buf

object DataConstants {
    val BIT_MASK = IntArray(32)

    init {
        for (i in BIT_MASK.indices) {
            BIT_MASK[i] = (1 shl i) - 1
        }
    }

}
