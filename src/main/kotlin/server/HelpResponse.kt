package server

import kotlinx.serialization.Serializable

@Serializable
data class HelpResponse(
    val project: String,
    val question: String,
    val answer: String
)