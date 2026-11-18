package app.blue.sms.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderIdsTest {
    @Test fun `operator prefix, header and service suffix`() {
        val s = SenderIds.parse("JM-FEDBNK-S")
        assertEquals("FEDBNK", s.header)
        assertEquals(SenderSuffix.S, s.suffix)
        assertFalse(s.isPhoneNumber)
    }

    @Test fun `promotional suffix, lower case`() {
        val s = SenderIds.parse("vm-zomato-p")
        assertEquals("ZOMATO", s.header)
        assertEquals(SenderSuffix.P, s.suffix)
    }

    @Test fun `old format without suffix`() {
        val s = SenderIds.parse("AX-HDFCBK")
        assertEquals("HDFCBK", s.header)
        assertNull(s.suffix)
    }

    @Test fun `bare header`() {
        val s = SenderIds.parse("ZOMATO")
        assertEquals("ZOMATO", s.header)
        assertNull(s.suffix)
    }

    @Test fun `phone number`() {
        val s = SenderIds.parse("+919876543210")
        assertTrue(s.isPhoneNumber)
        assertNull(s.suffix)
    }

    @Test fun `unknown one-letter tail is not a suffix`() {
        val s = SenderIds.parse("JD-ABCDEF-X")
        assertNull(s.suffix)
        assertEquals("ABCDEF-X", s.header)
    }
}
