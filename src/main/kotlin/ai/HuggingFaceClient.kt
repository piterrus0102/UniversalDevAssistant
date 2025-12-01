package ai

import config.AIConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Клиент для работы с HuggingFace API
 * Использует Qwen/Qwen2.5-7B-Instruct
 */
class HuggingFaceClient(private val config: AIConfig) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    
    private val apiUrl = config.api_url ?: "https://router.huggingface.co/v1/chat/completions"
    
    /**
     * Отправить вопрос к HuggingFace модели (с историей сообщений)
     */
    fun ask(messages: List<HFMessage>): String {
        logger.debug { "📤 Отправка запроса к HuggingFace API (${config.model})" }
        logger.debug { "История: ${messages.size} сообщений" }
        
        try {
            val requestBody = HFRequest(
                model = config.model,
                messages = messages,
                max_tokens = config.max_tokens,
                stream = false,
                temperature = 0.4,
                top_p = 0.95
            )
            
            val requestJson = json.encodeToString(requestBody)
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Authorization", "Bearer ${config.api_key}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofSeconds(120))
                .build()
            
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                val errorBody = response.body()
                logger.error { "HuggingFace API error: ${response.statusCode()}" }
                logger.error { "Response body: $errorBody" }
                
                if (response.statusCode() == 503 && errorBody.contains("loading", ignoreCase = true)) {
                    throw RuntimeException("⏳ Модель ${config.model} загружается. Попробуйте через 20-30 секунд.")
                }
                
                throw RuntimeException("HuggingFace API returned status ${response.statusCode()}: $errorBody")
            }
            
            logger.debug { "📥 Получен ответ от HuggingFace API" }
            
            val hfResponse = json.decodeFromString<HFResponse>(response.body())
            
            var answer = hfResponse.choices.firstOrNull()?.message?.content
                ?: throw RuntimeException("Empty response from HuggingFace")
            
            // Удаляем thinking блоки <think>...</think> для Qwen
            val thinkPattern = Regex("<think>[\\s\\S]*?</think>")
            val thinkMatches = thinkPattern.findAll(answer)
            if (thinkMatches.count() > 0) {
                logger.debug { "🧠 Thinking content найден, удаляем из ответа" }
                answer = answer.replace(thinkPattern, "").trim()
            }
            
            logger.debug { "Response length: ${answer.length} chars" }
            
            return answer
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при обращении к HuggingFace API" }
            throw RuntimeException("Failed to get response from HuggingFace: ${e.message}", e)
        }
    }
    
    /**
     * Отправить вопрос к HuggingFace модели (простой вариант)
     */
    fun ask(prompt: String, systemPrompt: String? = null): String {
        val messages = mutableListOf<HFMessage>()
        
        if (systemPrompt != null) {
            messages.add(HFMessage(role = "system", content = systemPrompt))
        }
        
        messages.add(HFMessage(role = "user", content = prompt))
        
        return ask(messages)
    }
    
    /**
     * Проверка работоспособности API
     */
    fun healthCheck(): Boolean {
        return try {
            ask("Hello", systemPrompt = "Reply with just 'OK'")
            true
        } catch (e: Exception) {
            logger.error(e) { "Health check failed" }
            false
        }
    }
}

// ============================================================================
// Модели данных для HuggingFace API
// ============================================================================

@Serializable
data class HFRequest(
    val model: String,
    val messages: List<HFMessage>,
    val max_tokens: Int,
    val stream: Boolean = false,
    val temperature: Double = 0.7,
    val top_p: Double = 0.95
)

@Serializable
data class HFMessage(
    val role: String,
    val content: String
)

@Serializable
data class HFResponse(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<HFChoice>,
    val usage: HFUsage? = null
)

@Serializable
data class HFChoice(
    val index: Int? = null,
    val message: HFMessage,
    val finish_reason: String? = null
)

@Serializable
data class HFUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

