@file:JvmName("EnvConfig")

package de.littletrotter.xpdf

/** Thin wrapper around [System.getenv] so tests can intercept via mockStatic on class EnvConfig. */
internal fun readEnv(key: String): String? = System.getenv(key)
