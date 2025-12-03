package server.request_response

import kotlinx.serialization.Serializable

@Serializable
data class GitBranchResponse(
    val branch: String
)