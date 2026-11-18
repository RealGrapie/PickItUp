package com.grapie.pickitup

/** Pebble system fonts are ASCII-ish only: account names go to the watch transliterated. */
object WatchNames {
    private val ru = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya",
    )
    const val MAX = 18

    fun ascii(s: String): String = buildString {
        for (c in s) {
            val t = ru[c.lowercaseChar()]
            when {
                t != null -> append(if (c.isUpperCase()) t.replaceFirstChar { it.uppercaseChar() } else t)
                c.code in 0x20..0x7e -> append(c)
            }
        }
    }.trim().replace(Regex(" +"), " ")

    /**
     * Header / menu line for [account] among [accounts]: "OZON: Mama"; without a name just "WB", as
     * on the phone ("WB 2" only when another unnamed account of that marketplace needs telling
     * apart); the only account: "WB pickup code" (watchapps before 1.6 keep their own header then).
     */
    fun label(account: Account, accounts: List<Account>): String {
        val name = ascii(account.name.value)
        val title = account.provider.title.uppercase()
        val twins = accounts.filter { it.provider == account.provider && ascii(it.name.value).isEmpty() }
        return when {
            accounts.size == 1 && name.isEmpty() -> "$title pickup code"
            name.isEmpty() && twins.size > 1 -> "$title ${twins.indexOf(account) + 1}"
            name.isEmpty() -> title
            else -> "$title: $name"
        }.take(MAX)
    }

    /** Watch storage keeps one value per key up to 256 bytes: this many days fit with room to spare. */
    private const val MAX_DAYS = 4

    /** The days after today's code as "<from epoch s>|<code>|<text>" lines (watch: next_day()). */
    fun days(upcoming: List<Barcode>): String =
        upcoming.take(MAX_DAYS).joinToString("\n") { "${it.validFrom / 1000}|${ascii(it.code)}|${ascii(it.visible)}" }
}
