package jakarta.transaction

// Stand-ins carrying the Jakarta and Quarkus names the transaction rules match, so the rules are
// tested against exactly those names without pulling the application server APIs into this module.

enum class TxType { REQUIRED, REQUIRES_NEW }

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Transactional(val value: TxType = TxType.REQUIRED)
