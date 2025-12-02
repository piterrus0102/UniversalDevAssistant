package rag

import ai.HuggingFaceClient
import ai.HFMessage
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Reranker - Реранкинг результатов поиска для улучшения релевантности
 * 
 * Реализует два подхода:
 * 1. Threshold-based filtering - фильтрация по порогу similarity
 * 2. LLM-based reranking - использует LLM для оценки релевантности
 */
class Reranker(private val llmClient: HuggingFaceClient?) {
    
    init {
        logger.info { "[Reranker] Инициализирован" }
    }
    
    /**
     * Результат реранкинга с чанком
     */
    data class RankedChunk(
        val chunk: DocumentChunk,
        val similarity: Double,
        val llmScore: Double = 0.0
    )
    
    /**
     * Фильтрация по порогу similarity
     */
    fun filterByThreshold(chunks: List<Pair<DocumentChunk, Double>>, minSimilarity: Double = 0.25): List<RankedChunk> {
        logger.info { "[Reranker] === Пороговая фильтрация ===" }
        logger.info { "[Reranker] Порог: ${(minSimilarity * 100).toInt()}%" }
        logger.info { "[Reranker] Результатов до фильтрации: ${chunks.size}" }
        
        val filtered = chunks
            .filter { it.second >= minSimilarity }
            .map { RankedChunk(it.first, it.second) }
        
        logger.info { "[Reranker] Результатов после фильтрации: ${filtered.size}" }
        
        if (filtered.isEmpty()) {
            logger.warn { "[Reranker] ⚠️ Все результаты отфильтрованы (низкая релевантность)" }
        } else {
            logger.info { "[Reranker] ✓ Диапазон similarity: ${(filtered.last().similarity * 100).toInt()}% - ${(filtered.first().similarity * 100).toInt()}%" }
        }
        
        return filtered
    }
    
    /**
     * LLM-based реранкинг - модель оценивает релевантность каждого чанка
     */
    suspend fun rerankWithLLM(query: String, rankedChunks: List<RankedChunk>, maxChunks: Int = 20): List<RankedChunk> {
        if (llmClient == null) {
            logger.warn { "[Reranker] LLM client не предоставлен, пропускаем LLM-реранкинг" }
            return rankedChunks
        }
        
        // Оптимизация: обрабатываем только топ-N чанков по similarity
        val chunksToProcess = rankedChunks.take(maxChunks)
        
        logger.info { "[Reranker] === LLM-based реранкинг ===" }
        if (rankedChunks.size > maxChunks) {
            logger.info { "[Reranker] ⚡ ОПТИМИЗАЦИЯ: Обрабатываем топ-${maxChunks} из ${rankedChunks.size} чанков" }
            logger.info { "[Reranker] (Остальные отсекаются для ускорения)" }
        }
        logger.info { "[Reranker] Оценка ${chunksToProcess.size} чанков..." }
        
        val scoredChunks = mutableListOf<RankedChunk>()
        
        chunksToProcess.forEachIndexed { index, rankedChunk ->
            logger.info { "[Reranker] [${index + 1}/${chunksToProcess.size}] Оценка: ${rankedChunk.chunk.path} (чанк ${rankedChunk.chunk.chunkIndex})" }
            
            // Формируем промпт для LLM
            val systemMessage = HFMessage(
                role = "system",
                content = """Ты - эксперт по оценке релевантности документов для проекта ${rankedChunk.chunk.path}.

                            Твоя задача: оценить насколько чанк документа может помочь ответить на вопрос пользователя.
                            
                            ВАЖНО:
                            - Оценивай ЛИБЕРАЛЬНО - если чанк хоть как-то связан с темой вопроса, ставь >= 0
                            - Если в чанке есть ключевые слова из вопроса - это уже >= 5 баллов
                            - Ставь низкие оценки (0) ТОЛЬКО если чанк вообще о другом
                            
                            ФОРМАТ ОТВЕТА:
                            Верни ТОЛЬКО ОДНО ЧИСЛО от 0 до 10 (без точки, без текста)"""
            )
            
            val userMessage = HFMessage(
                role = "user",
                content = """ВОПРОС: $query

                ЧАНК ДОКУМЕНТА:
                Файл: ${rankedChunk.chunk.path}
                Чанк: ${rankedChunk.chunk.chunkIndex}
                
                Контент: ${rankedChunk.chunk.content}
                
                Оцени релевантность чанка вопросу (0-10):"""
            )
            
            try {
                // Вызываем LLM для оценки
                val response = llmClient.ask(listOf(systemMessage, userMessage))
                
                // Парсим оценку
                val scoreText = response.trim()
                val score = scoreText.toDoubleOrNull() ?: 0.0
                
                if (score !in 0.0..10.0) {
                    scoredChunks.add(rankedChunk.copy(llmScore = 0.0))
                } else {
                    scoredChunks.add(rankedChunk.copy(llmScore = score))
                    logger.debug { "[Reranker]   LLM оценка: ${score.toInt()}/10" }
                }
            } catch (e: Exception) {
                logger.error(e) { "[Reranker] ❌ Ошибка оценки через LLM" }
                scoredChunks.add(rankedChunk.copy(llmScore = 0.0))
            }
        }
        
        // Сортируем по LLM оценке
        val sorted = scoredChunks.sortedByDescending { it.llmScore }
        
        logger.info { "[Reranker] ✓ Топ-3 чанка после LLM оценки:" }
        sorted.take(3).forEachIndexed { i, chunk ->
            logger.info { "[Reranker]   ${i + 1}. [LLM: ${chunk.llmScore.toInt()}/10, Sim: ${(chunk.similarity * 100).toInt()}%] ${chunk.chunk.path} (чанк ${chunk.chunk.chunkIndex})" }
        }
        
        return sorted
    }
    
    /**
     * Гибридный реранкинг: пороговая фильтрация + LLM-реранкинг
     */
    suspend fun hybridRerank(
        query: String,
        chunks: List<Pair<DocumentChunk, Double>>,
        options: RerankOptions = RerankOptions()
    ): RerankResult {
        logger.info { "[Reranker] ====================================" }
        logger.info { "[Reranker] 🔄 ГИБРИДНЫЙ РЕРАНКИНГ" }
        logger.info { "[Reranker] ====================================" }
        logger.info { "[Reranker] Входных результатов: ${chunks.size}" }
        logger.info { "[Reranker] Порог similarity: ${(options.minSimilarity * 100).toInt()}%" }
        logger.info { "[Reranker] Целевое количество: топ-${options.topK}" }
        
        // ШАГ 1: Пороговая фильтрация
        logger.info { "[Reranker] === ШАГ 1/3: Пороговая фильтрация ===" }
        val afterThreshold = filterByThreshold(chunks, options.minSimilarity)
        
        if (afterThreshold.isEmpty()) {
            logger.error { "[Reranker] ❌ Нет результатов после пороговой фильтрации" }
            return RerankResult(
                chunks = emptyList(),
                reason = "no_results_after_threshold"
            )
        }
        
        // ШАГ 2: LLM-реранкинг
        logger.info { "[Reranker] === ШАГ 2/3: LLM-реранкинг ===" }
        val afterLLM = rerankWithLLM(query, afterThreshold, options.maxChunksForLLM)
        
        // ШАГ 3: Берем топ-K
        logger.info { "[Reranker] === ШАГ 3/3: Финальный отбор (топ-${options.topK}) ===" }
        val finalChunks = afterLLM.take(options.topK)
        
        logger.info { "[Reranker] ✅ Финальных чанков: ${finalChunks.size}" }
        finalChunks.forEachIndexed { i, chunk ->
            logger.info { "  ${i + 1}. [LLM: ${chunk.llmScore.toInt()}/10, Sim: ${(chunk.similarity * 100).toInt()}%] ${chunk.chunk.path}" }
        }
        
        logger.info { "[Reranker] ====================================" }
        logger.info { "[Reranker] ✅ РЕРАНКИНГ ЗАВЕРШЕН" }
        logger.info { "[Reranker] ====================================" }
        
        return RerankResult(
            chunks = finalChunks,
            reason = "success",
            stats = RerankStats(
                initial = chunks.size,
                afterThreshold = afterThreshold.size,
                afterLLM = afterLLM.size,
                final = finalChunks.size
            )
        )
    }
    
    data class RerankOptions(
        val minSimilarity: Double = 0.25,  // Порог для первичной фильтрации
        val topK: Int = 3,                  // Количество финальных результатов
        val maxChunksForLLM: Int = 20       // Макс. чанков для LLM-оценки (для скорости)
    )
    
    data class RerankResult(
        val chunks: List<RankedChunk>,
        val reason: String,
        val stats: RerankStats? = null
    )
    
    data class RerankStats(
        val initial: Int,
        val afterThreshold: Int,
        val afterLLM: Int,
        val final: Int
    )
}

