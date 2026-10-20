package de.littletrotter.application.dto

data class SubjectStatus(
    val name: String,
    val retainedMessages: Long,
    val consumerLag: Long,
)
