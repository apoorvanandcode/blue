package app.blue.sms.domain.ai

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Disguises one SMS before it goes to Gemini, and undoes the disguise on the answer.
 *
 * - Every number becomes a random number of the same length (dates and times stay real).
 * - Names of people and shops become made-up words; ordinary English, bank wording and each bank's own
 *   boilerplate stay readable so Gemini can still understand the message.
 * - UPI ids and links are replaced; IFSC codes keep their shape with fake digits.
 * - The user's own name never passes.
 *
 * The swap list ([Masked.swaps]) stays on the phone. Tested on 20,171 real SMS (see DECISIONS.md, 1.2.0).
 */
class SmsMasker(private val vocab: MaskVocabulary, private val random: Random = Random.Default) {
    private val en get() = vocab.english

    fun mask(sender: String, body: String): Masked = Run(family(sender)).mask(body)

    private inner class Run(private val fam: String) {
        private val fwd = HashMap<String, String>()
        private val rev = LinkedHashMap<String, String>()
        private val senderWords = vocab.senderWords[fam].orEmpty()
        private val boilerplate = vocab.boilerplate[fam].orEmpty()

        fun mask(body: String): Masked {
            data class Span(val start: Int, val end: Int, val kind: String, val m: MatchResult)
            val spans = mutableListOf<Span>()
            fun add(m: MatchResult, kind: String) {
                val a = m.range.first; val b = m.range.last + 1
                if (spans.none { it.start < b && a < it.end }) spans += Span(a, b, kind, m)
            }
            URL.findAll(body).forEach { add(it, "url") }
            UPI.findAll(body).forEach { if (isUpi(it.groupValues[1], it.groupValues[2])) add(it, "upi") }
            IFSC.findAll(body).forEach { add(it, "ifsc") }
            DATE.findAll(body).forEach { add(it, "date") }
            spans.sortBy { it.start }

            val out = StringBuilder()
            var i = 0
            for (s in spans) {
                out.append(plain(body.substring(i, s.start)))
                val seg = body.substring(s.start, s.end)
                when (s.kind) {
                    "url" -> out.append(sub(seg, "https://x.example/" + fakeWord("abcd").lowercase()))
                    "upi" -> {
                        val fakeLocal = sub(s.m.groupValues[1], fakeWord("abcdef").lowercase() + fakeDigits("12"))
                        // real handles (ybl, oksbi, kotak811) stay; digits in an odd handle are faked too
                        val handle = s.m.groupValues[2].let { h -> if (h.count(Char::isDigit) >= 3) NUM.replace(h) { sub(it.value, fakeDigits(it.value)) } else h }
                        val full = fakeLocal + "@" + handle
                        rev[full] = seg; out.append(full)
                    }
                    "ifsc" -> {
                        val tail = s.m.groupValues[2].map { if (it.isDigit()) ('0' + random.nextInt(10)) else it }.joinToString("")
                        val fake = s.m.groupValues[1] + "0" + tail
                        rev[fake] = seg; out.append(fake)
                    }
                    else -> out.append(seg)   // dates and times stay real
                }
                i = s.end
            }
            out.append(plain(body.substring(i)))
            return Masked(out.toString(), rev)
        }

        private fun plain(text: String): String {
            val numbered = NUM.replace(text) { sub(it.value, fakeDigits(it.value)) }
            return WORD.replace(numbered) { m ->
                val w = m.value
                val end = m.range.last + 1
                // a bank code glued to digits (SBIN…, UTIB…, N…) stays
                if (end < numbered.length && numbered[end].isDigit() && isUpperPy(w) && w.length <= 5) w
                else if (allowed(w)) w else sub(w, fakeWord(w))
            }
        }

        private fun sub(real: String, fakeIn: String): String {
            fwd[real]?.let { return it }
            var fake = fakeIn
            while (fake in rev) fake = if (real.all(Char::isDigit)) fakeDigits(real) else fake + "x"
            fwd[real] = fake; rev[fake] = real
            return fake
        }

        private fun isUpi(local: String, handle: String): Boolean {
            if (local.lowercase() in STATUS || (en.isWord(local) && isUpperPy(handle))) return false
            return handle.lowercase() in UPI_HANDLES || isLowerPy(handle) || local.any { it.isDigit() || it in "._-" }
        }

        /** True when a word may be shown to Gemini as it is. */
        private fun allowed(w: String): Boolean {
            val lw = w.lowercase()
            if (lw in vocab.sharedNames) return false                       // the user's own name
            if (lw in STATUS) return true
            if ('\'' in w) { val base = w.substringBefore('\''); return base.isEmpty() || allowed(base) }
            if (ACCOUNT_LABEL.matches(w) || A_C_X.matches(w)) return true    // A/cX1234, cX, XX
            if (isUpperPy(w) && w.length in 2..3) return true               // short codes (CHG, RET, UMN)
            val parts = CAMEL.findAll(w).map { it.value }.toList()
            if (parts.size > 1 && parts.all { en.isWord(it) || it.lowercase() in vocab.commonWords }) return true   // AdviceDomestic
            if (lw in FINANCE3 || lw in vocab.commonWords || lw in boilerplate) return true
            if ((lw.endsWith("ed") || lw.endsWith("ing")) && en.isWord(lw)) return true   // verbs: almost never names
            if (lw.length >= 3 && fam.isNotEmpty() && (fam.lowercase().contains(lw) || lw.contains(fam.lowercase()))) return true   // sender's own brand
            if (lw in EXTRA_FINANCE || lw in senderWords) return true
            if (lw in FINANCE || w.length == 1) return true
            if (lw.all { it == 'x' }) return true
            if (lw in GENERIC) return true
            if (lw in vocab.templateWords && (en.isWord(lw) || lw in FINANCE)) return true
            return isLowerPy(w) && en.isWord(lw)
        }

        private fun fakeDigits(d: String): String {
            var s = String(CharArray(d.length) { '0' + random.nextInt(10) })
            if (d[0] != '0' && d.length > 1) s = ('1' + random.nextInt(9)) + s.substring(1)
            if (d.any { it != '0' } && s.all { it == '0' }) s = s.dropLast(1) + "7"
            return s
        }

        private fun fakeWord(w: String): String {
            val len = maxOf(3, w.length)
            var s: String
            while (true) {
                s = String(CharArray(len) { i -> if (i % 2 == 0) CONS[random.nextInt(CONS.length)] else VOW[random.nextInt(VOW.length)] })
                if (!en.isWord(s) && s !in FINANCE && s !in vocab.commonWords && s !in FINANCE3 && s !in EXTRA_FINANCE) break
            }
            return when {
                isUpperPy(w) -> s.uppercase()
                w[0].isUpperCase() -> s.replaceFirstChar { it.uppercase() }
                else -> s
            }
        }
    }

    companion object {
        /** "AD-HDFCBK-S" -> "HDFCBK". */
        fun family(sender: String): String = sender.replace(Regex("^[A-Z]{2}-|-[A-Z]$"), "")

        private const val CONS = "bdfgklmnprstvz"
        private const val VOW = "aeiou"

        private fun words(s: String) = s.trim().split(Regex("\\s+")).toSet()

        val FINANCE = words("""a/c ac acct account accounts avl avbl bal balance upi imps neft rtgs nach ecs emi inr rs usd mrp txn trxn tran transaction
            transactions ref refno rrn utr vpa ifsc mandate autopay auto pay debit debited credit credited spent withdrawn withdrawal atm pos
            card cards cc dc credit debit bank banking netbanking ib mb sms otp info id no not you your dear customer call block reissue
            hdfc hdfcbk sbi sbiupi icici axis kotak federal fedbnk onecard sbm paytm jupiter idfc yes pnb bob canara unity indusind rbl au
            fi bhim npci gpay phonepe ybl ybk oksbi okaxis okhdfcbank okicici paytm ibl axl apl upi axisbank sbin hdfcbank fbl jupiteraxis
            ptsbi ptyes ptaxis pthdfc waaxis wahdfcbank kotak811 icic ikwik freecharge amazonpay rapl idfcbank
            via on at to from by for of the a an and or is was has have been with your has in into thru through your via towards
            successfully successful success failed declined reversed refund refunded reversal cashback interest salary charges charge fee
            info alert alerts dear sir madam team regards thank thanks customer care helpline toll free
            jan feb mar apr may jun jul aug sep sept oct nov dec january february march april june july august september october november december
            mon tue wed thu fri sat sun am pm ist hrs hr""")
        val EXTRA_FINANCE = words("""wallet cash cashback bonus reward rewards points voucher coupon promo offer offers loan premium policy sip mutual fund
            nav units folio dividend ipo shares demat bill bills due payment payments paid received sent order delivered shipped dispatched refund
            recharge plan postpaid prepaid data validity limit available avail statement minimum total amount amt txns purchase purchased
            merchant beneficiary payee payer remitter linked mobile number email kyc pan aadhaar gst tds income tax interest fd rd deposit
            maturity matured renewal renewed insurance claim emi installment instalment overdue outstanding charges penalty late fee waived
            game games gaming play contest winnings withdraw added credit debit card account savings current salary reversal reversed failed pending""")
        val GENERIC = words("pvt ltd limited private services service india online technologies technology solutions enterprises store stores mart retail co com corp corporation company the and of finance financial capital securities bank")
        val FINANCE3 = words("""cardmember cardholder congrats congratulations dear valued customer cr dr lakh lakhs lac crore pnr pax upi ifsc utr rrn neft imps rtgs nach ecs pf epf uan nps ppf demat nsdl cdsl folio isin
            autopay e-mandate emandate mandate vpa qr pos ecom atm emi gst tds itr""")
        val STATUS = words("""cancelled canceled revoked success successful active failed failure submitted allotted pending declined rejected
            approved approve approving requested request processed initiated reversed refunded credited debited received sent paid
            frm fr thru towards vide dt dtd dated info int amt txn umn max freq min avl avbl bal ref no cpin gstn gstin challan nach ecs chg ret ch cms pnr pax
            intt int. ncd cr dr app acc accnt acct ac txn trf trfd tfr neft-cr imps-cr upi-cr nach-cr cms""")
        val UPI_HANDLES = words("""ybl ibl axl apl upi paytm pty ptsbi ptyes ptaxis pthdfc oksbi okaxis okhdfcbank okicici okbizaxis ikwik freecharge
            axisbank axisb sbi hdfcbank icici kotak yesbank yesbankltd fbl federal idfcbank idfcfirst indus aubank rbl jupiteraxis waaxis wahdfcbank
            wasbi waicici amazon amazonpay apay rapl airtel airtelpaymentsbank pingpay timecosmos abfspay mairtel jio tapicici naviaxis
            superyes dlb cnrb barodampay unionbankofindia uboi pnb boi citi citigold dbs kbl kvb tjsb equitas ujjivan slice slc fam""")

        private const val MONTHS = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*"
        private val DATE_PATTERNS = listOf(
            """\d{4}-\d{2}-\d{2}(?:[ T:]\d{2}:\d{2}(?::\d{2})?)?""",
            """\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?""",
            """\d{1,2}[- ]?(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[- ,]*(?:\d{2,4})?(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?""",
            """\b\d{1,2}:\d{2}(?::\d{2})?(?:\s?[AaPp][Mm])?""",
            """\b\d{1,2}[-/ ]$MONTHS[-/ ,]*\d{2,4}\b""",
            """\b$MONTHS\.? \d{1,2},? \d{4}\b""",
            """\b$MONTHS[- ]\d{2,4}\b""",
            """\b(?:0?[1-9]|[12]\d|3[01])[-/](?:0?[1-9]|1[0-2])\b(?![-/]\d)""",
            """\b(?:0?[1-9]|1[0-2])/(?:19|20)\d{2}\b""",
            """\b\d{2}(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\d{2}\b""",
            """\b\d{1,2}(?:st|nd|rd|th)\b(?:[ -]?(?:of )?$MONTHS)?(?:[ ,'-]*\d{2,4})?""",
            """$MONTHS'\d{2}\b""",
            """\bFY\s?\d{2,4}(?:\s?-\s?\d{2,4})?\b""",
            """\b(?:19|20)\d{2}\s?-\s?(?:19|20)?\d{2}\b""",
            """\b(?:19|20)\d{2}\b""",
        )
        private val DATE = Regex(DATE_PATTERNS.joinToString("|") { "(?:$it)" }, RegexOption.IGNORE_CASE)
        private val URL = Regex("""https?://\S+|(?<![@\w.])(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:in|com|co|me|ly|io|app|link|page|net|org|bank|pe|gl|to)(?:/\S*)?(?![\w@])""", RegexOption.IGNORE_CASE)
        private val UPI = Regex("""\b([A-Za-z0-9._-]+)@([A-Za-z][A-Za-z0-9]*)\b""")
        private val IFSC = Regex("""\b([A-Z]{4})0([A-Z0-9]{6})\b""")
        private val WORD = Regex("[A-Za-z][A-Za-z']*")
        private val NUM = Regex("""\d+""")
        private val CAMEL = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])")
        private val ACCOUNT_LABEL = Regex("[A-Za-z]?[Xx]+")
        private val A_C_X = Regex("[Aa]/?[Cc][Xx]+")

        /** Python's str.isupper(): has letters, none lowercase. */
        internal fun isUpperPy(s: String) = s.any { it.isLetter() } && s.none { it.isLowerCase() }
        internal fun isLowerPy(s: String) = s.any { it.isLetter() } && s.none { it.isUpperCase() }
    }
}

/** A disguised SMS and the private list of swaps (fake -> real) needed to undo it. */
class Masked(val text: String, val swaps: Map<String, String>) {
    private val lower = swaps.entries.associate { it.key.lowercase() to it.value }

    /** Puts real values back into a text field Gemini returned (merchant, ref, UPI id...). */
    fun restoreText(s: String?): String? {
        if (s == null) return null
        if (s.trim().lowercase() in EMPTY) return null
        return WHOLE.replace(s) { m ->
            val t = m.value
            swaps[t] ?: lower[t.lowercase()] ?: run {
                if ('@' in t) {
                    val loc = t.substringBefore('@'); val h = t.substringAfter('@')
                    (swaps[loc] ?: lower[loc.lowercase()])?.let { return@run "$it@$h" }
                }
                PART.replace(t) { p -> part(p.value) }
            }
        }
    }

    private fun part(t: String): String {
        swaps[t]?.let { return it }
        lower[t.lowercase()]?.let { return it }
        if (t.length >= 2 && t.all(Char::isDigit)) {
            for ((fake, real) in swaps) {
                if (fake.length == real.length && fake.all(Char::isDigit) && t in fake) {
                    val k = fake.indexOf(t); return real.substring(k, k + t.length)
                }
            }
        }
        return t
    }

    /** Finds the number Gemini picked in the disguised text and swaps its digits back. Null if it isn't in the text. */
    fun restoreAmount(x: Double?): Double? {
        if (x == null) return null
        for (m in NUMSTR.findAll(text)) {
            val v = m.value.replace(",", "").toDoubleOrNull() ?: continue
            if (abs(v - x) < 0.005) {
                val real = Regex("\\d+").replace(m.value) { swaps[it.value] ?: it.value }
                return real.replace(",", "").toDoubleOrNull()
            }
        }
        return null
    }

    /**
     * The account's last digits come from the disguised account number itself (Gemini sometimes pads or slips a digit),
     * then go back to the real digits. Null when it can't be tied to an account number in the SMS.
     */
    fun restoreLast4(x: String?): String? {
        val xd = x?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() } ?: return null
        val tokens = ACCT_TOKEN.findAll(text).map { it.groupValues[1] }.toList()
        val stripped = xd.trimStart('0')
        for (t in tokens) {
            val real = swaps[t] ?: continue
            if (t.endsWith(xd) || (stripped.isNotEmpty() && t.endsWith(stripped))) return real.takeLast(min(4, real.length))
        }
        val unique = tokens.filter { swaps[it] != null }.toSet()
        if (unique.size == 1) return swaps[unique.first()]!!.let { it.takeLast(min(4, it.length)) }
        for ((fake, real) in swaps) {
            if (fake.all(Char::isDigit) && real.all(Char::isDigit) && fake.endsWith(xd)) return real.takeLast(min(4, real.length))
        }
        return null
    }

    /** Gemini's answer with every disguised value swapped back. */
    fun restore(r: AiReply): AiReply = r.copy(
        amount = restoreAmount(r.amount),
        merchant = restoreText(r.merchant),
        upiId = restoreText(r.upiId),
        accountLast4 = restoreLast4(r.accountLast4),
        refNo = restoreText(r.refNo),
        title = restoreText(r.title),
        trackingRef = restoreText(r.trackingRef),
        bank = r.bank?.takeIf { it.trim().lowercase() !in EMPTY },
    )

    private companion object {
        val EMPTY = setOf("", "none", "null", "unknown", "n/a", "na")
        val WHOLE = Regex("[A-Za-z0-9._@'/-]+")
        val PART = Regex("[A-Za-z']+|\\d+")
        val NUMSTR = Regex("""\d[\d,]*(?:\.\d+)?""")
        val ACCT_TOKEN = Regex("""(?:[Xx*]+|(?:A/?c|Acct|Account|Card|ending|a/c no\.?)\s*[:.-]?\s*[Xx*]*)\s?(\d{2,})""", RegexOption.IGNORE_CASE)
    }
}
