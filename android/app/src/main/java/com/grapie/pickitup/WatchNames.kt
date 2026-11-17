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

    /** Header / menu line for account number [index] (0-based): "OZON: Mama", or "OZON 2" without a name. */
    fun label(account: Account, index: Int): String {
        val name = ascii(account.name.value)
        val title = account.provider.title.uppercase()
        return (if (name.isEmpty()) "$title ${index + 1}" else "$title: $name").take(MAX)
    }
}
