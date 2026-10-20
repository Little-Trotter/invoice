package de.littletrotter.application.system.port.outbound

/**
 * Port for querying embedded library information (name, version, optional description).
 */
fun interface LibraryInfoService {

    fun listLibraries(): List<LibraryInfo>

    data class LibraryInfo(
        val name: String,
        val version: String,
        val description: String = "",
    )
}
