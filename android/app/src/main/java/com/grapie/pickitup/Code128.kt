package com.grapie.pickitup

/** Code 128 encoder, same set B/C switching as the watchapp's code128.c. */
object Code128 {
    // Symbol values 0..105: six bar/space widths, packed 2 bits each as (w - 1).
    private val PATTERNS = intArrayOf(
        0x455, 0x545, 0x554, 0x116, 0x125, 0x215, 0x152, 0x161,
        0x251, 0x512, 0x521, 0x611, 0x059, 0x149, 0x158, 0x095,
        0x185, 0x194, 0x590, 0x509, 0x518, 0x491, 0x581, 0x848,
        0x815, 0x905, 0x914, 0x851, 0x941, 0x950, 0x446, 0x464,
        0x644, 0x026, 0x206, 0x224, 0x062, 0x242, 0x260, 0x422,
        0x602, 0x620, 0x04a, 0x068, 0x248, 0x086, 0x0a4, 0x284,
        0x884, 0x428, 0x608, 0x482, 0x4a0, 0x488, 0x806, 0x824,
        0xa04, 0x842, 0x860, 0xa40, 0x8c0, 0x530, 0xe00, 0x017,
        0x035, 0x107, 0x134, 0x305, 0x314, 0x053, 0x071, 0x143,
        0x170, 0x341, 0x350, 0x710, 0x503, 0xc80, 0x701, 0x2c0,
        0x01d, 0x10d, 0x11c, 0x0d1, 0x1c1, 0x1d0, 0xc11, 0xd01,
        0xd10, 0x44c, 0x4c4, 0xc44, 0x00e, 0x02c, 0x20c, 0x0c2,
        0x0e0, 0xc02, 0xc20, 0x08c, 0x0c8, 0x80c, 0xc08, 0x431,
        0x413, 0x419,
    )
    private val STOP = intArrayOf(2, 3, 3, 1, 1, 1, 2)
    private const val CODE_C = 99
    private const val CODE_B = 100
    private const val START_B = 104
    private const val START_C = 105

    /** Alternating bar/space widths in modules (starting with a bar), or null if not encodable. */
    fun encode(text: String): IntArray? {
        if (text.isEmpty() || text.any { it.code !in 32..126 }) return null
        fun digitRun(i: Int): Int {
            var n = 0
            while (i + n < text.length && text[i + n].isDigit()) n++
            return n
        }

        val values = mutableListOf<Int>()
        var setC = digitRun(0) >= 4 || (digitRun(0) == text.length && text.length >= 2 && text.length % 2 == 0)
        values += if (setC) START_C else START_B
        var i = 0
        while (i < text.length) {
            if (setC) {
                if (digitRun(i) >= 2) {
                    values += (text[i] - '0') * 10 + (text[i + 1] - '0')
                    i += 2
                    continue
                }
                values += CODE_B
                setC = false
            }
            val run = digitRun(i)
            if (run >= 4 && run % 2 == 0) {
                values += CODE_C
                setC = true
                continue
            }
            values += text[i].code - 32
            i++
        }
        values += (values[0] + values.drop(1).mapIndexed { k, v -> (k + 1) * v }.sum()) % 103

        val widths = mutableListOf<Int>()
        for (v in values) {
            val p = PATTERNS[v]
            for (k in 0 until 6) widths += ((p shr (2 * (5 - k))) and 3) + 1
        }
        widths += STOP.toList()
        return widths.toIntArray()
    }
}
