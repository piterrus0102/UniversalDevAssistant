package rag

import kotlinx.serialization.Serializable

/**
 * Модель документа для RAG индекса
 * 
 * Хранит проиндексированный файл и предоставляет методы для поиска
 */
@Serializable
data class Document(
    val path: String,
    val content: String,
    val lines: Int,
    val size: Long
) {
    /**
     * Проверка вхождения запроса в содержимое
     */
    fun matches(query: String, caseSensitive: Boolean = false): Boolean {
        return if (caseSensitive) {
            content.contains(query)
        } else {
            content.contains(query, ignoreCase = true)
        }
    }
    
    /**
     * Извлечение релевантного фрагмента с контекстом
     */
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
    
    /**
     * Разбить документ на чанки для векторизации
     * Чанки с overlap чтобы не терять контекст на границах
     */
    fun toChunks(chunkSize: Int = 1000, overlap: Int = 300): List<DocumentChunk> {
        if (content.length <= chunkSize) {
            return listOf(DocumentChunk(path, content, 0))
        }
        
        val chunks = mutableListOf<DocumentChunk>()
        var start = 0
        var chunkIndex = 0
        
        while (start < content.length) {
            val end = minOf(start + chunkSize, content.length)
            val chunkContent = content.substring(start, end)
            
            chunks.add(DocumentChunk(path, chunkContent, chunkIndex))
            
            // Следующий чанк начинается с overlap
            start += chunkSize - overlap
            chunkIndex++
        }
        
        return chunks
    }
}

/**
 * Чанк документа для векторизации
 */
@Serializable
data class DocumentChunk(
    val path: String,
    val content: String,
    val chunkIndex: Int
) {
    val id: String get() = "$path#$chunkIndex"
}


