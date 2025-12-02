package server

import kotlinx.serialization.Serializable

@Serializable
data class DocInfo(
    val path: String
)