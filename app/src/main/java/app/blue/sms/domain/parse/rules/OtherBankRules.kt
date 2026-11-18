package app.blue.sms.domain.parse.rules

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction.IN
import app.blue.sms.domain.model.Direction.OUT
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.UpcomingKind

val IciciRules = BankRules(
    id = "icici", bank = "ICICI Bank", headers = listOf("ICICIT", "ICICIB", "ICICIO"),
    rules = listOf(
        ignoreRule("icici.collect_request", """has requested money"""),
        Rule("icici.imps_credit", """icici bank account ${ACCT} is credited with ${AMT} on (?<date>\d{1,2}-[a-z]{3}-\d{2}) by (?<to>.+?)\.\s*imps ref\.?\s*no\.?\s*(?<ref>\d+)""") {
            txn(IN, PaymentMethod.NETBANKING)
        },
        Rule("icici.self_transfer", """icici bank acct ${ACCT} debited with ${AMT} on (?<date>\d{1,2}-[a-z]{3}-\d{2}) & acct (?<to>[x*\d]+) credited\.?\s*(?:imps|neft|rtgs):?\s*(?<ref>\d+)""") {
            txn(OUT, PaymentMethod.NETBANKING, merchant = "Acct " + g("to"))
        },
        Rule("icici.credited_info", """icici bank account ${ACCT} credited:\s*${AMT} on (?<date>\d{1,2}-[a-z]{3}-\d{2})\.\s*info\s*(?<to>.+?)\.\s*(?:available balance|\z)""") {
            txn(IN, PaymentMethod.OTHER)
        },
    ),
)

val OneCardRules = BankRules(
    id = "onecard", bank = "SBM OneCard", headers = listOf("ONECRD"),
    rules = listOf(
        ignoreRule("onecard.declined", """declin|locked your card"""),
        ignoreRule("onecard.bill_payment", """received payment against your onecard"""),
        Rule("onecard.emandate_upcoming", """upcoming debit for the emandate registered on (?<to>.+?) of ${AMT} on (?<date>[\d/]+)""") {
            upcoming(UpcomingKind.MANDATE)
        },
        Rule("onecard.spent", """spent ${AMT} at (?<to>.+?) with your sbm one credit card ending in ${ACCT}""") {
            txn(OUT, PaymentMethod.CARD, AccountType.CREDIT_CARD)
        },
        Rule("onecard.refund", """received a refund of ${AMT} from (?<to>.+?) on your sbm one credit card""") {
            txn(IN, PaymentMethod.CARD, AccountType.CREDIT_CARD, isRefund = true)
        },
    ),
)

val AxisRules = BankRules(
    id = "axis", bank = "Axis Bank", headers = listOf("AXISBK"),
    rules = listOf(
        ignoreRule("axis.collect_request", """has requested money"""),
        Rule("axis.mandate_created", """(?:upi mandate set up for|upi mandate has been successfully created towards) (?<to>.+?) for ${AMT}""") {
            upcoming(UpcomingKind.MANDATE)
        },
    ),
)

val UnityRules = BankRules(
    id = "unity", bank = "Unity Bank", headers = listOf("UNTYBK", "UNTYBA"),
    rules = listOf(
        Rule("unity.interest", """savings interest of ${AMT} has been credited to your a/c ${ACCT} on (?<date>[\d/]+)""") {
            txn(IN, PaymentMethod.OTHER, merchant = "Savings interest")
        },
    ),
)

val AuRules = BankRules(
    id = "au", bank = "AU Bank", headers = listOf("AUBANK"),
    rules = listOf(
        Rule("au.transfer_debit", """your (?:neft|imps|rtgs) payment with utr (?<ref>\S+) for ${AMT} is debited on (?<date>[\d/]+)""") {
            txn(OUT, PaymentMethod.NETBANKING)
        },
    ),
)

/** No transaction formats seen yet. Known sender, so non-money SMS are ignored and money SMS wait for Gemini. */
val KotakRules = BankRules(id = "kotak", bank = "Kotak", headers = listOf("KOTAKB"), rules = emptyList())

val ALL_BANKS = listOf(
    FederalRules, JupiterRules, SbiRules, HdfcRules, IciciRules, OneCardRules, AxisRules, UnityRules, AuRules, KotakRules,
)
