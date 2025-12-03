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

