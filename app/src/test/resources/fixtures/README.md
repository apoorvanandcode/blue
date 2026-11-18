# SMS fixtures

Each `.txt` file holds blocks like this. `FixtureTest` runs every block through `RuleClassifier`
(time zone Asia/Kolkata) and checks only the `expect` keys you list.

```
=== short description
sender: AD-FEDBNK-S
received: 2026-11-17 02:44
spam_list: ZOMATO, FOO        (optional)
body:
the SMS text, can span lines
expect:
class: TRANSACTION            (TRANSACTION, UPCOMING_FINANCE, UPCOMING_DELIVERY, SPAM, IGNORE, none = undecided)
rule: federal.upi_debit
bank: Federal Bank
amount: 1060.82               (none = no amount)
direction: OUT
account: X4321
account_type: SAVINGS
method: UPI
merchant: ATRIA CONVER
upi: xxxx@ybl
ref: 316482153969
at: 2026-11-17 02:44
refund: true
kind: MANDATE
due: 2026-11-03 09:09
delivery: SHIPPED
tracking: IT905034519IN
```

Adding a new SMS format = one `Rule` in `domain/parse/rules` + one block here.
Samples are real SMS with account numbers, references, balances and names masked.
