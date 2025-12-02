package server

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val project: String,
    val mcpServers: Int,
    val mcpServerNames: List<String>,
    val gitEnabled: Boolean
)