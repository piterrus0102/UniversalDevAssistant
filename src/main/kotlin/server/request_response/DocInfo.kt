package server.request_response

import kotlinx.serialization.Serializable

@Serializable
data class DocInfo(
    val path: String
)