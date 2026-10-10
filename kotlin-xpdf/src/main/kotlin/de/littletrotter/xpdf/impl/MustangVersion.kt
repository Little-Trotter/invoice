package de.littletrotter.xpdf.impl

/** F2 — the Mustang/ZUGFeRD generator version for the report's version block. Only resolvable
 *  here: `org.mustangproject` is a dependency of the worker (kotlin-xpdf), not of
 *  kotlin-invoice, so its `pom.properties` never lands on the reader's classpath — this is
 *  why the version travels in the payload instead of being re-derived on read. */
internal object MustangVersion {
    val value: String by lazy { resolve() }

    /** Test seam — [value] memoizes across the whole JVM (a Kotlin `object` singleton), so the
     *  missing-resource/exception fallback branches below are otherwise unreachable once any
     *  test has already resolved the real classpath resource. Defaults to the real classloader
     *  for production/normal test use. */
    internal fun resolve(loader: ClassLoader = Thread.currentThread().contextClassLoader): String =
        try {
            val props = java.util.Properties()
            val resource = loader.getResourceAsStream("META-INF/maven/org.mustangproject/library/pom.properties")
            resource?.use { props.load(it) }
            props.getProperty("version") ?: "unbekannt"
        } catch (_: Exception) {
            "unbekannt"
        }
}
