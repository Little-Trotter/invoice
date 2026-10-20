package io.quarkus.narayana.jta

/** Stand-in for the programmatic transaction entry point. */
object QuarkusTransaction {
    fun requiringNew(): Int = 0
}
