package app.blue.sms.domain.ai

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Plain English words (Webster's 1934, public domain), used to tell ordinary words from names.
 * Sorted, binary-searched: about 210k words in one array.
 */
class EnglishWords(sortedWords: Array<String>) {
    private val words = sortedWords

    operator fun contains(w: String): Boolean = words.binarySearch(w) >= 0

    /** An English word, counting inflections the list lacks (requested, approving, debits, charges). */
    fun isWord(word: String): Boolean {
        val w = word.lowercase()
        if (w in this) return true
        for ((suf, rep) in SUFFIXES) {
            if (w.endsWith(suf) && w.length - suf.length >= 3 && (w.dropLast(suf.length) + rep) in this) return true
        }
        // stopping -> stop
        if (w.length > 5 && w.endsWith("ing") && w[w.length - 4] == w[w.length - 5] && w.dropLast(4) in this) return true
        return false
    }

    companion object {
        private val SUFFIXES = listOf(
            "ies" to "y", "ied" to "y", "ing" to "", "ing" to "e", "ed" to "", "ed" to "e", "es" to "", "s" to "",
            "er" to "", "ers" to "", "ly" to "", "ment" to "", "ments" to "",
        )

        /** The bundled asset: gzipped, one word per line. Not named .gz, which Android's packager would unzip and rename. */
        const val ASSET = "english_words.dat"

        /** Reads the word list, gzipped or plain. */
        fun load(input: InputStream): EnglishWords {
            val buffered = java.io.BufferedInputStream(input)
            buffered.mark(2)
            val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val stream = if (gzipped) GZIPInputStream(buffered) else buffered
            val list = stream.bufferedReader().useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList() }
            list.sort()
            return EnglishWords(list.toTypedArray())
        }
    }
}

/**
 * What the masker learns from the phone's own SMS: which words are bank boilerplate (safe to show)
 * and which non-dictionary words show up in many banks' greetings (the user's own name: always hidden).
 * Built on the phone; never sent anywhere.
 */
class MaskVocabulary(
    val english: EnglishWords,
    /** Words in 50+ SMS from 3+ senders. */
    val templateWords: Set<String>,
    /** Per sender: dictionary words in at least 3% (and 5) of that sender's SMS. */
    val senderWords: Map<String, Set<String>>,
    /** Dictionary words used by 4+ different senders: ordinary language. */
    val commonWords: Set<String>,
    /** Per sender: any word in at least 10% (and 20) of that sender's SMS: app names, sign-offs. */
    val boilerplate: Map<String, Set<String>>,
    /** Non-dictionary words that are boilerplate for 3+ senders: almost always the user's own name. */
    val sharedNames: Set<String>,
) {
    companion object {
        private val WORD = Regex("[A-Za-z][A-Za-z']*")

        fun build(english: EnglishWords, sms: List<Pair<String, String>>): MaskVocabulary {
            val count = HashMap<String, Int>()
            val families = HashMap<String, MutableSet<String>>()
            val byFamily = HashMap<String, MutableList<Set<String>>>()
            for ((sender, body) in sms) {
                val fam = SmsMasker.family(sender)
                val words = WORD.findAll(body).map { it.value.lowercase() }.toSet()
                byFamily.getOrPut(fam) { mutableListOf() }.add(words)
                for (w in words) {
                    count[w] = (count[w] ?: 0) + 1
                    families.getOrPut(w) { HashSet() }.add(fam)
                }
            }
            val template = count.filter { (w, n) -> n >= 50 && families[w]!!.size >= 3 }.keys
            val common = families.filter { (w, fs) -> w.length >= 2 && fs.size >= 4 && english.isWord(w) }.keys
            val senderWords = HashMap<String, Set<String>>()
            val boilerplate = HashMap<String, Set<String>>()
            for ((fam, list) in byFamily) {
                if (list.size < 5) continue
                val c = HashMap<String, Int>()
                for (ws in list) for (w in ws) c[w] = (c[w] ?: 0) + 1
                senderWords[fam] = c.filter { (w, n) ->
                    n >= 5 && n >= 0.03 * list.size && (english.isWord(w) || w in SmsMasker.FINANCE || w in SmsMasker.EXTRA_FINANCE)
                }.keys
                if (list.size >= 20) boilerplate[fam] = c.filter { (_, n) -> n >= 20 && n >= 0.10 * list.size }.keys
            }
            val nameCount = HashMap<String, Int>()
            val notNames = SmsMasker.FINANCE + SmsMasker.FINANCE3 + SmsMasker.STATUS + SmsMasker.EXTRA_FINANCE + SmsMasker.GENERIC + SmsMasker.UPI_HANDLES
            for (ws in boilerplate.values) for (w in ws) {
                if (w.length > 3 && !english.isWord(w) && w !in notNames) nameCount[w] = (nameCount[w] ?: 0) + 1
            }
            return MaskVocabulary(english, template, senderWords, common, boilerplate, nameCount.filter { it.value >= 3 }.keys)
        }
    }
}
