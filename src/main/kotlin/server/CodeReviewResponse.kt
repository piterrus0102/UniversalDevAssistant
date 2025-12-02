package server

import kotlinx.serialization.Serializable

@Serializable
data class CodeReviewResponse(
    val pr_number: Int,
    val review: String,
    val tools_used: List<String>
)