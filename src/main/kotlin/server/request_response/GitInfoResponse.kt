package server.request_response

import kotlinx.serialization.Serializable

@Serializable
data class GitInfoResponse(
    val currentBranch: String,
    val status: String,
    val lastCommit: String
)