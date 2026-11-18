package app.blue.sms.domain.model

// Stored in Room by name. Never rename a constant without a migration.

enum class SmsClass { TRANSACTION, UPCOMING_FINANCE, UPCOMING_DELIVERY, SPAM, IGNORE }

/** Who produced a classification or extraction. */
enum class ProcessedBy { RULES, AI, USER }

enum class Direction { IN, OUT }

enum class PaymentMethod { UPI, CARD, NETBANKING, AUTO_DEBIT, ATM, OTHER }

/** INTERNAL_TRANSFER: money moved between the user's own accounts. Kept, but never counted as spending or income. */
enum class TxnStatus { PENDING, CONFIRMED, SKIPPED, DUPLICATE, NOT_A_TRANSACTION, INTERNAL_TRANSFER }

enum class AccountType { SAVINGS, CREDIT_CARD, DEBIT_CARD }

enum class ForWhomType { ME, TAG, CONTACT }

enum class UpcomingKind { SIP, MANDATE, AUTO_DEBIT, DELIVERY }

enum class UpcomingStatus { PROPOSED, ACTIVE, CLOSED, REJECTED }
