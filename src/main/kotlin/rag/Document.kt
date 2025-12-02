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
     * Разбить документ на чанки по параграфам (семантическое разбиение)
     * Не режет слова и предложения посередине
     */
    fun toChunks(maxChunkSize: Int = 1500): List<DocumentChunk> {
        if (content.length <= maxChunkSize) {
            return listOf(DocumentChunk(path, content.trim(), 0))
        }
        
        // Разбиваем по параграфам (двойной перенос строки)
        val paragraphs = content.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        val chunks = mutableListOf<DocumentChunk>()
        var currentChunk = StringBuilder()
        var chunkIndex = 0
        
        for (paragraph in paragraphs) {
            // Если параграф сам по себе больше лимита - разбить по предложениям
            if (paragraph.length > maxChunkSize) {
                // Сначала сохраняем текущий чанк если есть
                if (currentChunk.isNotEmpty()) {
                    chunks.add(DocumentChunk(path, currentChunk.toString().trim(), chunkIndex++))
                    currentChunk = StringBuilder()
                }
                
                // Разбиваем большой параграф по предложениям
                val sentences = paragraph.split(Regex("(?<=[.!?])\\s+"))
                for (sentence in sentences) {
                    if (currentChunk.length + sentence.length > maxChunkSize && currentChunk.isNotEmpty()) {
                        chunks.add(DocumentChunk(path, currentChunk.toString().trim(), chunkIndex++))
                        currentChunk = StringBuilder()
                    }
                    if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                    currentChunk.append(sentence)
                }
            } else {
                // Обычный параграф - добавляем к текущему чанку
                if (currentChunk.length + paragraph.length + 2 > maxChunkSize && currentChunk.isNotEmpty()) {
                    chunks.add(DocumentChunk(path, currentChunk.toString().trim(), chunkIndex++))
                    currentChunk = StringBuilder()
                }
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(paragraph)
            }
        }
        
        // Добавляем последний чанк
        if (currentChunk.isNotEmpty()) {
            chunks.add(DocumentChunk(path, currentChunk.toString().trim(), chunkIndex))
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


