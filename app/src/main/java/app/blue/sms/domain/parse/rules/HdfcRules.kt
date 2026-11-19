package app.blue.sms.domain.parse.rules

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction.IN
import app.blue.sms.domain.model.Direction.OUT
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.UpcomingKind

private val CARD_DATE = """(?<date>\d{4}-\d{2}-\d{2}:\d{2}:\d{2}:\d{2})"""

/** "BLOCK DC" in the footer means a debit card; HDFC card SMS otherwise come from the credit card. */
private fun Hit.hdfcCardType() =
    if (Regex("(?i)block\\s+dc").containsMatchIn(sms.body)) AccountType.DEBIT_CARD else AccountType.CREDIT_CARD

val HdfcRules = BankRules(
    id = "hdfc", bank = "HDFC Bank", headers = listOf("HDFCBK", "HDFCBN"),
    rules = listOf(
        // Decided 2026-11-17: the "Spent" SMS makes the card; this one would be a duplicate.
        ignoreRule("hdfc.autopay_success", """autopay \(e-mandate\) success"""),
        ignoreRule("hdfc.card_bill_payment", """payment of ${AMT} (?:was credited to your card|received towards your credit card)"""),
        ignoreRule("hdfc.declined", """declined|autopay failed|has failed"""),
        // The "Money Deposited" / "Amt Deducted" SMS that follows makes the card.
        ignoreRule("hdfc.transfer_initiated", """(?:rtgs|neft|imps) transaction initiated"""),
        Rule("hdfc.transfer_deposited", """(?:rtgs|neft|imps) money deposited\s*~\s*${AMT}\s*~\s*to (?<to>[^~]+?)\s*~\s*txn no:?\s*(?<ref>[^~\s]+)\s*~\s*on (?<date>[\d-]+) at (?<time>[\d:]+)""") {
            txn(OUT, PaymentMethod.NETBANKING)
        },
        Rule("hdfc.amt_deducted", """amt deducted!\s*${AMT} from your hdfc bank a/c ${ACCT} for (?:neft|rtgs|imps) transaction""") {
            txn(OUT, PaymentMethod.NETBANKING)
        },
        Rule("hdfc.card_upcoming", """(?<cur>usd|inr|eur|gbp|rs)\.?\s?(?<amt>\d[\d,]*(?:\.\d{1,2})?) will be debited on (?<date>[\d/]+) from hdfc bank card ${ACCT} for (?<to>[^\n]+)""") {
            val inr = g("cur").equals("inr", true) || g("cur").equals("rs", true)
            upcoming(UpcomingKind.AUTO_DEBIT, amountPaise = if (inr) amount() else null)
        },
        Rule("hdfc.atm", """withdrawn ${AMT} from hdfc bank card ${ACCT} at (?<to>.+?) on $CARD_DATE""") {
            txn(OUT, PaymentMethod.ATM, AccountType.DEBIT_CARD)
        },
        Rule("hdfc.atm_alt", """${AMT} withdrawn from hdfc bank card ${ACCT} at (?<to>.+?) on $CARD_DATE""") {
            txn(OUT, PaymentMethod.ATM, AccountType.DEBIT_CARD)
        },
        Rule("hdfc.card_spent", """spent ${AMT} on hdfc bank card ${ACCT} at (?<to>.+?) on $CARD_DATE""") {
            txn(OUT, PaymentMethod.CARD, hdfcCardType())
        },
        Rule("hdfc.card_amount_spent", """amount spent:\s*${AMT} with hdfc bank credit card ${ACCT} at (?<to>.+?) on $CARD_DATE""") {
            txn(OUT, PaymentMethod.CARD, AccountType.CREDIT_CARD)
        },
        Rule("hdfc.card_reversed", """transaction reversed!\s*on hdfc bank credit card ${ACCT}\s+amt:\s*${AMT} by (?<to>.+?) on $CARD_DATE""") {
            txn(IN, PaymentMethod.CARD, AccountType.CREDIT_CARD, isRefund = true)
        },
        Rule("hdfc.card_refund", """${AMT} refunded by (?<to>.+?) on (?<date>\d{1,2}/[a-z]{3}/\d{4}) & adjusted against hdfc bank credit card ${ACCT}""") {
            txn(IN, PaymentMethod.CARD, AccountType.CREDIT_CARD, isRefund = true)
        },
        Rule("hdfc.money_transfer", """money transfer:\s*${AMT} from hdfc bank a/c ${ACCT} on (?<date>[\d-]+) to (?<to>.+?)\s+upi:\s*(?<ref>\d+)""") {
            txn(OUT, PaymentMethod.UPI)
        },
        Rule("hdfc.autopay_active", """autopay active!\s*for (?<to>.+?)\s+starting:\s*(?<date>[\d/]+).*?on hdfc bank card ${ACCT}""") {
            upcoming(UpcomingKind.MANDATE)
        },
        Rule("hdfc.card_no_otp", """${AMT} without otp/pin hdfc bank card ${ACCT} at (?<to>.+?) on $CARD_DATE""") {
            txn(OUT, PaymentMethod.CARD, hdfcCardType())
        },
        Rule("hdfc.upi_sent", """sent ${AMT}\s+from hdfc bank a/c ${ACCT}\s+to (?<to>.+?)\s+on (?<date>[\d/]+)\s+ref (?<ref>\d+)""") {
            txn(OUT, PaymentMethod.UPI)
        },
        Rule("hdfc.upi_credit", """${AMT} credited to hdfc bank a/c ${ACCT} on (?<date>[\d-]+) from vpa (?<upi>\S+)\s*\(upi (?<ref>\d+)\)""") {
            txn(IN, PaymentMethod.UPI, merchant = g("upi")?.trimStart('*'))
        },
        Rule("hdfc.upi_debit", """hdfc bank:\s*${AMT} debited from a/c ${ACCT} on (?<date>[\d/]+) to (?<to>.+?)\s*\(upi ref no\.?\s*(?<ref>\d+)\)""") {
            txn(OUT, PaymentMethod.UPI)
        },
        Rule("hdfc.received", """received!\s*${AMT} in hdfc bank a/c ${ACCT}\s+on (?<date>[\d-]+)\s+for (?:imps|neft|rtgs)\s*-?(?<to>.+?)-?\s+(?<ref>\d{6,})""") {
            txn(IN, PaymentMethod.NETBANKING)
        },
    ),
)
