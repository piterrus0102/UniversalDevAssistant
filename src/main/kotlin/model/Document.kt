package model

import kotlinx.serialization.Serializable

@Serializable
data class Document(
    val path: String,
    val content: String,
    val lines: Int,
    val size: Long
) {
    fun matches(query: String, caseSensitive: Boolean = false): Boolean {
        return if (caseSensitive) {
            content.contains(query)
        } else {
            content.contains(query, ignoreCase = true)
        }
    }
    
    fun getRelevantSnippet(query: String, contextLines: Int = 3): String {
        val lines = content.lines()
        val matchingLines = mutableListOf<Int>()
        
        lines.forEachIndexed { index, line ->
            if (line.contains(query, ignoreCase = true)) {
                matchingLines.add(index)
            }
        }
        
        if (matchingLines.isEmpty()) return ""
        
        val snippets = matchingLines.map { lineIndex ->
            val start = maxOf(0, lineIndex - contextLines)
            val end = minOf(lines.size, lineIndex + contextLines + 1)
            lines.subList(start, end).joinToString("\n")
        }
        
        return snippets.joinToString("\n\n---\n\n")
    }
}

