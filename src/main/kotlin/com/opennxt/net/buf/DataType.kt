package com.opennxt.net.buf

enum class DataType(
    val bytes: Int
) {
    BYTE(1),

    SHORT(2),

    MEDIUM(3),

    INT(4),

    LONG(8)

}
