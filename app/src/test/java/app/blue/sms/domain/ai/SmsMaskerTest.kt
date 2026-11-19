package app.blue.sms.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** All SMS here are made up. */
class SmsMaskerTest {
    private val english = File("src/main/assets/" + EnglishWords.ASSET).inputStream().use(EnglishWords::load)

    // A small made-up inbox: three banks greet the same (made-up) user name "Zorvanek" in every SMS.
    private val inbox: List<Pair<String, String>> = buildList {
        for (sender in listOf("AD-HDFCBK-S", "AX-FEDBNK-S", "JM-SBIUPI-S")) repeat(25) { i ->
            add(sender to "Dear Zorvanek, Rs ${100 + i}.00 debited from A/c XX${4000 + i} via UPI. Download YONO app.")
        }
    }
    private val masker = SmsMasker(MaskVocabulary.build(english, inbox), Random(7))

    private val hdfc = "Sent Rs.1,250.00 From HDFC Bank A/C *4821 To Pritesh Malhotra On 14/11/26 Ref 612398450172. " +
        "Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"

    @Test fun `numbers and names are replaced, bank wording and dates stay`() {
        val m = masker.mask("AD-HDFCBK-S", hdfc)
        for (secret in listOf("1,250.00", "4821", "Pritesh", "Malhotra", "612398450172", "18002586161", "7308080808")) {
            assertFalse("$secret leaked: ${m.text}", secret in m.text)
        }
        for (kept in listOf("Sent Rs.", "From HDFC Bank A/C", "To", "On 14/11/26", "Ref", "Not You? Call", "SMS BLOCK UPI")) {
            assertTrue("'$kept' should stay: ${m.text}", kept in m.text)
        }
    }

    @Test fun `the user's own name never passes`() {
        val m = masker.mask("AD-HDFCBK-S", "Dear Zorvanek, your statement is ready")
        assertFalse(m.text, "Zorvanek" in m.text)
        assertTrue(m.text.startsWith("Dear "))
    }

    @Test fun `answers are swapped back to the real values`() {
        val m = masker.mask("AD-HDFCBK-S", hdfc)
        val fakeAmount = Regex("""Rs\.([\d,]+\.\d\d)""").find(m.text)!!.groupValues[1].replace(",", "").toDouble()
        val fakeName = Regex("""To (\S+ \S+) On""").find(m.text)!!.groupValues[1]
        val fakeRef = Regex("""Ref (\d{12})""").find(m.text)!!.groupValues[1]
        val fakeAcct = Regex("""\*(\d{4})""").find(m.text)!!.groupValues[1]
        val back = m.restore(AiReply(smsClass = "transaction", amount = fakeAmount, merchant = fakeName, refNo = fakeRef, accountLast4 = fakeAcct))
        assertEquals(1250.0, back.amount!!, 0.001)
        assertEquals("Pritesh Malhotra", back.merchant)
        assertEquals("612398450172", back.refNo)
        assertEquals("4821", back.accountLast4)
    }

    @Test fun `last 4 comes from the account number even when Gemini slips`() {
        val m = masker.mask("JM-SBIUPI-S", "Dear Customer, Your A/C XXXXX736092 has a debit by NACH of Rs 609.37 on 28/09/23.")
        val fake = Regex("""X{5}(\d{6})""").find(m.text)!!.groupValues[1]
        assertEquals("6092", m.restoreLast4(fake.takeLast(4)))
        assertEquals("6092", m.restoreLast4(fake.takeLast(3)))          // too few digits
        assertEquals("6092", m.restoreLast4("0" + fake.takeLast(3)))    // padded
    }

    @Test fun `UPI ids are replaced and come back whole`() {
        val m = masker.mask("AX-FEDBNK-S", "Rs 50.00 sent to ramesh.k99@oksbi via UPI")
        assertFalse(m.text, "ramesh" in m.text)
        val fakeUpi = Regex("""(\S+@oksbi)""").find(m.text)!!.groupValues[1]
        assertEquals("ramesh.k99@oksbi", m.restoreText(fakeUpi))
        // "debited@SBI" is wording, not a UPI id
        assertTrue(masker.mask("JM-SBIUPI-S", "Rs 5 debited@SBI").text.contains("debited@SBI"))
    }

    @Test fun `IFSC keeps its shape, links are replaced, "none" becomes null`() {
        val m = masker.mask("AD-HDFCBK-S", "NEFT Cr-SBIN0004321-ACME LTD. Visit https://hdfc.bank.in/abc")
        assertTrue(m.text, Regex("""SBIN0\d{6}""").containsMatchIn(m.text))
        assertFalse(m.text, "0004321" in m.text)
        assertFalse(m.text, "hdfc.bank.in" in m.text)
        assertNull(m.restoreText("none"))
        assertNull(m.restoreAmount(99999.0))   // a number that isn't in the SMS
    }
}
