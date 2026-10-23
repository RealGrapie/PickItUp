package com.grapie.pickitup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Code128Test {
    /** Symbol widths of each value, read back from the encoder through single-symbol inputs is
     *  circular, so this is the standard table written as width strings (ISO/IEC 15417). */
    private val table = listOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232",
    )

    /** Minimal independent decoder: widths -> symbol values -> checksum -> text (sets B and C). */
    private fun decode(widths: IntArray): String {
        assertEquals("2331112", widths.takeLast(7).joinToString(""))  // stop
        val body = widths.dropLast(7)
        assertEquals(0, body.size % 6)
        val values = body.chunked(6).map { s -> table.indexOf(s.joinToString("")).also { assertTrue("bad symbol $s", it >= 0) } }
        val check = (values[0] + values.drop(1).dropLast(1).mapIndexed { k, v -> (k + 1) * v }.sum()) % 103
        assertEquals("checksum", check, values.last())
        var setC = when (values[0]) { 105 -> true; 104 -> false; else -> error("start ${values[0]}") }
        val sb = StringBuilder()
        // In set C, 99 is the digits "99"; only 100 switches (to B). In set B, 99 switches to C.
        for (v in values.drop(1).dropLast(1)) when {
            setC && v == 100 -> setC = false
            setC -> sb.append("%02d".format(v))
            v == 99 -> setC = true
            else -> sb.append((v + 32).toChar())
        }
        return sb.toString()
    }

    @Test fun roundTripsOzonLikeCodes() {
        val rnd = Random(42)
        repeat(300) {
            val user = (1..rnd.nextInt(5, 11)).joinToString("") { rnd.nextInt(10).toString() }
            val tail = (1..4).joinToString("") { rnd.nextInt(10).toString() }
            val code = "$user*$tail"
            assertEquals(code, decode(Code128.encode(code)!!))
        }
    }

    @Test fun roundTripsMixedText() {
        for (s in listOf("A", "12", "123", "1234", "abc123456def", "12345678*9012", "x1y22z333", "~ !")) {
            assertEquals(s, decode(Code128.encode(s)!!))
        }
    }

    @Test fun everySymbolIsElevenModules() {
        val w = Code128.encode("12345678*9012")!!
        assertEquals(13, w.takeLast(7).sum())
        w.dropLast(7).toList().chunked(6).forEach { assertEquals(11, it.sum()) }
    }

    @Test fun rejectsWhatItCannotEncode() {
        assertNull(Code128.encode(""))
        assertNull(Code128.encode("код"))
        assertNull(Code128.encode("tab\there"))
    }
}
