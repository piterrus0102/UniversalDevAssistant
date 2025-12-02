package server

import kotlinx.serialization.Serializable

@Serializable
data class DocsResponse(
    val count: Int,
    val documents: List<DocInfo>
)