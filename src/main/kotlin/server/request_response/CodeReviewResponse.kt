package server.request_response

import kotlinx.serialization.Serializable

@Serializable
data class CodeReviewResponse(
    val pr_number: Int,
    val review: String,
    val tools_used: List<String>
)