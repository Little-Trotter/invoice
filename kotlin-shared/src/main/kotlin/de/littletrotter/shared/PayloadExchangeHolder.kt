package de.littletrotter.shared

/**
 * Builds and owns a single [PayloadExchange] instance for a CDI producer to hand out: the
 * construct-plus-[close] plumbing little-trotter-invoice's and kotlin-xpdf's own
 * `PayloadExchangeProducer` both implemented identically. `@ApplicationScoped`/`@Produces`/
 * `@PreDestroy` wiring and the `@ConfigProperty` names stay local to each module's own producer —
 * kotlin-shared carries no CDI dependency by design (see [PayloadExchange]'s own KDoc).
 *
 * [payloadExchange] is a primary-constructor property so `AutoCloseable by
 * payloadExchange` can delegate [close] directly (Sonar rule S6514): Kotlin only allows `by <delegate>` against a
 * primary-constructor parameter/val (evaluated as part of the class header, before the body runs),
 * never against a property built later in the body (the former `by lazy` here made a manually
 * hand-written `close()` override — exactly the boilerplate `by` delegation exists to avoid — the
 * only option). Building [PayloadExchange] eagerly is safe: its own constructor only configures a
 * connection pool (lazy borrow-on-demand, see [PayloadExchange]'s own KDoc) and never dials a host,
 * so the no-arg constructor below still never opens a real connection — it just no longer defers
 * the harmless pool-config object itself.
 *
 * The no-arg secondary constructor exists only so Quarkus ArC can build a client proxy for a
 * subclass extending this one: a normal-scoped (`@ApplicationScoped`) bean whose only real
 * constructor takes arguments needs a no-arg constructor *somewhere* in its superclass chain for
 * ArC to synthesize the proxy's own constructor from — see
 * `jakarta.enterprise.inject.spi.DeploymentException: ... unproxyable bean class` if this is
 * missing. It is never used to build a real, working [PayloadExchange] (`"valkey"`/6379 dial a
 * host that is not this process); production code always goes through the CDI producer's own
 * `@Inject constructor`, which calls the two-arg constructor below with real config values.
 */
open class PayloadExchangeHolder(
    val payloadExchange: PayloadExchange,
) : AutoCloseable by payloadExchange {

    constructor(host: String, port: Int, password: String, database: Int) :
        this(PayloadExchange(host, port, password, database))

    constructor() : this(host = "valkey", port = 6379, password = "", database = 0)
}
