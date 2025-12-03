package server.request_response

import kotlinx.serialization.Serializable

@Serializable
data class CodeReviewRequest(
    val pr_number: Int
)