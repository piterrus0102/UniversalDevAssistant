package server.request_response

import kotlinx.serialization.Serializable

@Serializable
data class ReindexResponse(
    val status: String,
    val message: String,
    val durationMs: Long
)