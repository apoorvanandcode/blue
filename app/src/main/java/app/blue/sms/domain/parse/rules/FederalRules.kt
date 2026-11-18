package app.blue.sms.domain.parse.rules

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction.IN
import app.blue.sms.domain.model.Direction.OUT
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.UpcomingKind

/** Federal Bank (also Jupiter, which runs on a Federal Bank account). */
val FederalRules = BankRules(
    id = "federal", bank = "Federal Bank", headers = listOf("FEDBNK", "FEDFIB"),
    rules = listOf(
        Rule("federal.mandate_created", """created a mandate on (?<to>.+?) for a .*?starting from (?<date>[\d-]+).*?maximum amount of ${AMT}(?:.*?mandate ref no-?\s*(?<upi>[\w.\-]+@[\w.]+))?""") {
            upcoming(UpcomingKind.MANDATE)
        },
        Rule("federal.mandate_executed", """mandate with ref no-?\s*(?<upi>\S+) registered against (?<to>.+?) for ${AMT} successfully executed on (?<date>[\d-]+ [\d:]+).*?txn ref no\s*-?(?<ref>\d+)""") {
            txn(OUT, PaymentMethod.AUTO_DEBIT)
        },
        Rule("federal.upi_mandate_debit", """debited ${AMT} from a/c ${ACCT} for upi mandate towards (?<to>.+?) on (?<date>[\d-]+ [\d:]+)\.\s*ref:?\s*(?<ref>\d+)""") {
            txn(OUT, PaymentMethod.AUTO_DEBIT)
        },
        Rule("federal.upi_debit", """debited ${AMT} from a/c ${ACCT} on (?<date>\d{1,2}[a-z]{3}\d{2} \d{1,2}:\d{2}) via upi to (?<to>.+?)\.\s*ref\s*(?<ref>\d+)""") {
            txn(OUT, PaymentMethod.UPI)
        },
        Rule("federal.upi_sent", """${AMT} sent via upi on (?<date>[\d-]+) at (?<time>[\d:]+) to (?<to>.+?)\s*\.\s*ref:?\s*(?<ref>\d+)""") {
            txn(OUT, PaymentMethod.UPI)
        },
    ),
)

val JupiterRules = BankRules(
    id = "jupiter", bank = "Federal Bank", headers = listOf("ONJPTR", "JUPITR"),
    rules = listOf(
        Rule("jupiter.credit", """${AMT}\s+was credited to your federal bank account ${ACCT}""") {
            txn(IN, PaymentMethod.OTHER)
        },
    ),
)
