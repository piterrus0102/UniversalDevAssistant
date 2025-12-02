package server

import kotlinx.serialization.Serializable

@Serializable
data class CodeReviewRequest(
    val pr_number: Int
)