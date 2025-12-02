package mcp

import kotlinx.serialization.Serializable

@Serializable
data class GitInfo(
    val isGitRepo: Boolean,
    val currentBranch: String,
    val lastCommit: String,
    val modifiedFiles: List<String>,
    val status: String,
    val remote: String
)