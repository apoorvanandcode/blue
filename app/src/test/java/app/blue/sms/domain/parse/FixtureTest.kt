package app.blue.sms.domain.parse

import app.blue.sms.domain.parse.rules.ALL_BANKS
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Runs every block in src/test/resources/fixtures/\*.txt through the rules. See the README there. */
class FixtureTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val classifier = RuleClassifier(zone, ALL_BANKS)

    private data class Fixture(
        val file: String, val name: String, val sender: String, val received: Long,
        val spamList: Set<String>, val body: String, val expect: Map<String, String>,
    )

    private fun load(): List<Fixture> {
        val dir = File(requireNotNull(javaClass.classLoader!!.getResource("fixtures")).toURI())
        return dir.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }.flatMap { file ->
            file.readText().split(Regex("(?m)^=== ")).drop(1).map { block -> parse(file.name, block) }
        }
    }

    private fun parse(file: String, block: String): Fixture {
        val lines = block.lines()
        val name = lines.first().trim()
        val headers = mutableMapOf<String, String>()
        var i = 1
        while (i < lines.size && lines[i].trim() != "body:") {
            lines[i].split(":", limit = 2).takeIf { it.size == 2 }?.let { headers[it[0].trim()] = it[1].trim() }
            i++
        }
        val bodyStart = i + 1
        val expectAt = lines.indexOfFirst { it.trim() == "expect:" }
        require(expectAt > bodyStart) { "$file / $name: needs body: and expect: sections" }
        val body = lines.subList(bodyStart, expectAt).joinToString("\n").trimEnd()
        val expect = lines.drop(expectAt + 1).filter { it.isNotBlank() }.associate {
            val (k, v) = it.split(":", limit = 2); k.trim() to v.trim()
        }
        return Fixture(
            file, name,
            sender = requireNotNull(headers["sender"]) { "$file / $name: missing sender" },
            received = LocalDateTime.parse(requireNotNull(headers["received"]), fmt).atZone(zone).toInstant().toEpochMilli(),
            spamList = headers["spam_list"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            body = body,
            expect = expect,
        )
    }

    private fun time(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) } ?: "none"
    private fun str(v: Any?) = v?.toString() ?: "none"

    private fun actual(p: ParsedSms): Map<String, String> = mapOf(
        "class" to str(p.smsClass),
        "rule" to p.ruleId,
        "bank" to str(p.bank),
        "amount" to (p.amountPaise?.let(Money::format) ?: "none"),
        "direction" to str(p.direction),
        "account" to str(p.account),
        "account_type" to str(p.accountType),
        "method" to str(p.method),
        "merchant" to str(p.merchant),
        "upi" to str(p.upiId),
        "ref" to str(p.refNo),
        "at" to time(p.txnAt),
        "refund" to p.isRefund.toString(),
        "kind" to str(p.upcomingKind),
        "due" to time(p.dueAt),
        "delivery" to str(p.deliveryEvent),
        "tracking" to str(p.trackingRef),
    )

    @Test
    fun allFixturesParseAsExpected() {
        val fixtures = load()
        assertTrue("no fixtures found", fixtures.isNotEmpty())
        val failures = mutableListOf<String>()
        for (f in fixtures) {
            val got = actual(classifier.classify(SmsInput(f.sender, f.body, f.received), f.spamList))
            for ((key, want) in f.expect) {
                val have = got[key] ?: run { failures += "${f.file} / ${f.name}: unknown expect key '$key'"; continue }
                val wantNorm = if (key == "amount" && want != "none") Money.toPaise(want)?.let(Money::format) ?: want else want
                if (have != wantNorm) failures += "${f.file} / ${f.name}: $key expected <$wantNorm> but was <$have>  (rule ${got["rule"]})"
            }
        }
        println("Checked ${fixtures.size} fixtures, ${failures.size} mismatches")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n", prefix = "\n"))
    }

    @Test
    fun otpBodiesNeverGetMoneyClasses() {
        // Acceptance: OTP SMS are always ignore (and so never reach Gemini).
        val otp = classifier.classify(SmsInput("AD-HDFCBK-S", "Your OTP is 482913 for Rs. 2,000.00 at FLIPKART. Do not share.", 0))
        assertTrue(otp.ruleId == "otp")
    }
}
