package app.blue.sms.domain.parse.rules

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction.IN
import app.blue.sms.domain.model.Direction.OUT
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.UpcomingKind

val SbiRules = BankRules(
    id = "sbi", bank = "SBI",
    headers = listOf("SBIUPI", "CBSSBI", "SBIINB", "ATMSBI", "SBIBNK", "SBYONO", "SBIPSG", "SBICRD", "SBMSBI"),
    rules = listOf(
        ignoreRule("sbi.declined", """declined|dishonou?red"""),
        ignoreRule("sbi.mandate_revoked", """upi-mandate successfully revoked"""),
        Rule("sbi.upi_mandate_created", """upi-mandate successfully created towards (?<to>.+?) for ${AMT}""") {
            upcoming(UpcomingKind.MANDATE)
        },
        Rule("sbi.upi_mandate_created_alt", """upi-mandate for ${AMT} is successfully created towards (?<to>.+?)(?:\.\s|\s+-|\s*$)""") {
            upcoming(UpcomingKind.MANDATE)
        },
        Rule("sbi.nach_credit", """a/c ${ACCT} has a credit by nach-\s*(?<to>.+?) of ${AMT} on (?<date>[\d/]+)""") {
            txn(IN, PaymentMethod.OTHER)
        },
        Rule("sbi.autopay_scheduled", """upi autopay for (?<to>.+?) debit of ${AMT} is scheduled on \.?(?<date>[\d/]+)(?:.*?(?<upi>[\w.\-]+@[a-z]+))?""") {
            upcoming(UpcomingKind.AUTO_DEBIT)
        },
        Rule("sbi.emandate_active", """e-mandate .*? of rs\.?\s*(?<amt>\d[\d,]*(?:\.\d{1,2})?) in a/c ${ACCT} is active""") {
            upcoming(UpcomingKind.MANDATE, title = "E-mandate (SBI)")
        },
        Rule("sbi.upi_debit", """a/c ${ACCT} debited by (?<amt>\d[\d,]*(?:\.\d{1,2})?) on date (?<date>\d{1,2}[a-z]{3}\d{2}) trf to (?<to>.+?)\s+refno\s*(?<ref>\d+)""") {
            txn(OUT, PaymentMethod.UPI)
        },
        Rule("sbi.neft_credit", """${AMT} credited to your a/c no\.?\s*${ACCT} on (?<date>[\d/]+) through (?:neft|imps|rtgs) with utr (?<ref>\S+) by (?<to>.+?)(?:,|\s+info:)""") {
            txn(IN, PaymentMethod.NETBANKING)
        },
        Rule("sbi.imps_credit", """a/c no\.?\s*${ACCT} is credited by ${AMT} on (?<date>[\d-]+) by a/c linked to mobile \S+?-(?<to>.+?)\s*\(imps ref no\.?\s*(?<ref>\d+)\)""") {
            txn(IN, PaymentMethod.NETBANKING)
        },
        Rule("sbi.nach_debit", """a/c ${ACCT} has a debit by nach of ${AMT} on (?<date>[\d/]+)""") {
            txn(OUT, PaymentMethod.AUTO_DEBIT, merchant = "NACH")
        },
        Rule("sbi.credit_for", """a/c ${ACCT} has credit for (?<to>.+?) of ${AMT} on (?<date>[\d/]+)""") {
            txn(IN, PaymentMethod.OTHER)
        },
        Rule("sbi.yono_transfer", """ac ${ACCT} debited ${AMT} for transfer to (?<to>.+?) ac [x*\d]+ dt (?<date>[\d.]+) ref (?<ref>\d+)""") {
            txn(OUT, PaymentMethod.NETBANKING)
        },
        Rule("sbi.it_refund", """it refund amount of ${AMT} .*?credited to your account ${ACCT} on (?<date>[\d-]+)""") {
            txn(IN, PaymentMethod.OTHER, merchant = "Income Tax refund")
        },
        Rule("sbi.debit_card", """transaction number (?<ref>\d+) for ${AMT} by sbi debit card ${ACCT} done at (?<to>\S+) on (?<date>\d{1,2}[a-z]{3}\d{2}) at (?<time>[\d:]+)""") {
            txn(OUT, PaymentMethod.CARD, AccountType.DEBIT_CARD)
        },
    ),
)
