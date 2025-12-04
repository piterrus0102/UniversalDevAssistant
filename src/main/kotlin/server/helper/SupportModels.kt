package server.helper

import kotlinx.serialization.Serializable

/**
 * Запрос от пользователя на поддержку
 */
@Serializable
data class SupportRequest(
    val userName: String,
    val date: String,
    val title: String,
    val message: String,
    val answer: String = ""
)

/**
 * Контейнер для списка запросов
 */
@Serializable
data class SupportRequestsContainer(
    val requests: List<SupportRequest>
)

/**
 * Приоритет задачи
 */
enum class TicketPriority {
    HIGH,   // Критичные проблемы, блокеры, много пользователей затронуто
    NORMAL, // Стандартные улучшения и доработки
    LOW     // Небольшие улучшения, "было бы неплохо"
}

/**
 * Тикет на разработку/доработку
 * 
 * Создаётся на основе анализа answers.json - когда ответ службы поддержки
 * выявляет необходимость технической доработки
 */
@Serializable
data class Ticket(
    val id: String,
    val createdAt: String,
    val updatedAt: String,
    val title: String,
    val text: String,
    val suggestiveTechnicalDecision: String,
    val priority: String = "NORMAL" // HIGH, NORMAL, LOW
)

/**
 * Контейнер для списка тикетов
 */
@Serializable
data class TicketsContainer(
    val tickets: List<Ticket>
)

