package de.littletrotter.application.dto

data class ServiceStatus(
    val key: String,
    val instanceName: String,
    val target: String,
    val up: Boolean,
    val version: String = "",
)
