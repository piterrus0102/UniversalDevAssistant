package server

import kotlinx.serialization.Serializable

@Serializable
data class GitBranchResponse(
    val branch: String
)