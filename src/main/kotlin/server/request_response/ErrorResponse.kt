package server.request_response

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String
)