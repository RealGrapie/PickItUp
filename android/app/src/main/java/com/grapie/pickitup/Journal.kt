package com.grapie.pickitup

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The debug screen's journal. Stored as one line per entry: "<epoch ms>\t<account label>\t<text>"
 * (label empty when the line is about no account or there is just one). Lines from before that
 * format ("dd.MM HH:mm:ss  text", maybe "Ozon · Name: text") are still read.
 */
object Journal {
    data class Entry(val time: Long, val account: String?, val icon: String, val text: String)

    fun line(time: Long, account: String?, text: String) = "$time\t${account.orEmpty()}\t${text.replace('\n', ' ')}"

    private val old = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    private val oldAccount = Regex("^(\\S+ · [^:]{1,30}): ")

    fun parse(line: String): Entry? {
        val parts = line.split('\t', limit = 3)
        if (parts.size == 3 && parts[0].toLongOrNull() != null) {
            return entry(parts[0].toLong(), parts[1].ifEmpty { null }, parts[2])
        }
        if (line.length < 16) return null
        val time = runCatching { old.parse(line.substring(0, 14)) }.getOrNull() ?: return null
        // The old format has no year: this year, or last year if that would be in the future.
        val cal = Calendar.getInstance().apply { this.time = time }
        val now = Calendar.getInstance()
        cal.set(Calendar.YEAR, now.get(Calendar.YEAR))
        if (cal.after(now)) cal.add(Calendar.YEAR, -1)
        var rest = line.substring(14).trimStart()
        val account = oldAccount.find(rest)?.groupValues?.get(1)
        if (account != null) rest = rest.substring(account.length + 2)
        return entry(cal.timeInMillis, account, rest)
    }

    /** A leading emoji becomes the entry's icon. */
    private fun entry(time: Long, account: String?, text: String): Entry {
        val space = text.indexOf(' ')
        val head = if (space in 1..4) text.substring(0, space) else ""
        return if (head.isNotEmpty() && head.none { it.isLetterOrDigit() }) Entry(time, account, head, text.substring(space + 1))
        else Entry(time, account, "", text)
    }

    /** One row on screen: an entry, how many identical ones it stands for, notes under it. */
    data class Row(val entry: Entry, val count: Int = 1, val seconds: Int? = null, val tokens: String? = null)

    private val tokenPair = Regex("access:(\\w+)(\\(guest\\))? refresh:(\\w+)(\\(guest\\))?")
    private val tokenText = Regex("^(Токены входа|Login tokens)[^:]*: (.+) → (.+)$")

    private fun isFetch(e: Entry) = e.icon == "🔄" || e.text.startsWith("Запрашиваем штрихкод") || e.text.startsWith("Requesting the barcode")
    private fun isResult(e: Entry) = e.icon in setOf("✅", "❌", "🔑", "🛡️", "🌐")

    /** "access:759f refresh:496c" -> "759f/496c" (old entries carry the long form). */
    private fun compact(p: String) = tokenPair.replace(p) { m ->
        m.groupValues[1] + "/" + m.groupValues[3] + if (m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty()) " guest" else ""
    }

    /**
     * Entries (oldest first) -> rows, newest first: a request and its answer become one row (with
     * how long it took and the token renewal under it), unchanged tokens disappear, and runs of the
     * same line within 10 minutes collapse into one with a count.
     */
    fun rows(entries: List<Entry>): List<Row> {
        val out = ArrayList<Row>()
        val pending = HashMap<String?, Int>()  // account -> index of its open request row
        for (e in entries) {
            val tok = if (e.icon == "🔐") tokenText.find(e.text) else null
            if (tok != null) {
                val (a, b) = compact(tok.groupValues[2]) to compact(tok.groupValues[3])
                if (a == b) continue
                val i = pending[e.account]
                val note = "$a → $b"
                if (i != null) out[i] = out[i].copy(tokens = note) else out += Row(e.copy(text = e.text.substringBefore(':')), tokens = note)
                continue
            }
            if (isFetch(e)) {
                pending[e.account] = out.size
                out += Row(e)
                continue
            }
            val i = pending[e.account]
            if (isResult(e) && i != null) {
                val req = out[i]
                out[i] = Row(e, seconds = ((e.time - req.entry.time) / 1000).toInt(), tokens = req.tokens)
                pending.remove(e.account)
                continue
            }
            val last = out.lastOrNull()?.entry
            if (last != null && last.icon == e.icon && last.text == e.text && last.account == e.account && e.time - last.time < 600_000) {
                out[out.size - 1] = out.last().let { it.copy(entry = e, count = it.count + 1) }
                continue
            }
            out += Row(e)
        }
        return out.asReversed()
    }

    /** 0 = today, 1 = yesterday, ... (calendar days). */
    fun daysAgo(time: Long): Int {
        fun day(t: Long) = Calendar.getInstance().apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((day(System.currentTimeMillis()) - day(time)) / 86_400_000L).toInt()
    }
}
