# 🔌 Интеграция с AIAdvent4Thread

Этот проект интегрирован с компонентами из **AIAdvent4Thread**:

## 🎯 Использованные компоненты

### 1. HuggingFace LLM
- **Модель**: `Qwen/Qwen2.5-7B-Instruct`
- **API**: HuggingFace Router API
- **Ключ**: Взят из `/AIAdvent4Thread/mcp-proxy/.env`
- **Реализация**: `src/main/kotlin/ai/HuggingFaceClient.kt`

**Оригинал** (JavaScript): `/AIAdvent4Thread/mcp-proxy/server/HuggingFaceClient.js`

### 2. Ollama Векторизация
- **Модель**: `mxbai-embed-large`
- **URL**: `http://localhost:11434`
- **Реализация**: `src/main/kotlin/rag/OllamaClient.kt`

**Оригинал** (JavaScript): `/AIAdvent4Thread/rag-proxy/VectorizationClient.js`

### 3. RAG Service
- **Индексация документов** с векторизацией
- **Семантический поиск** через Ollama embeddings
- **Fallback** на keyword search если Ollama недоступна
- **Реализация**: `src/main/kotlin/rag/RAGService.kt`

**Оригинал** (JavaScript): `/AIAdvent4Thread/rag-proxy/RAGService.js`

---

## 📋 Конфигурация

Файл `config.yaml` использует ключи из AIAdvent4Thread:

```yaml
# AI - HuggingFace вместо Claude
ai:
  provider: "huggingface"
  model: "Qwen/Qwen2.5-7B-Instruct"
  api_key: "your_huggingface_api_key_here"  # Получите ключ на https://huggingface.co/settings/tokens
  api_url: "https://router.huggingface.co/v1/chat/completions"

# Векторизация - Ollama
vectorization:
  enabled: true
  ollama_url: "http://localhost:11434"
  model: "mxbai-embed-large"
```

---

## 🚀 Как запустить

### 1. Убедитесь, что Ollama запущена

```bash
# Проверка
curl http://localhost:11434

# Если не запущена, запустите:
ollama serve
```

### 2. Убедитесь, что модель установлена

```bash
ollama pull mxbai-embed-large
```

### 3. Запустите субагента

```bash
cd /Users/ruslanhafizov/Desktop/UniversalDevAssistant
./gradlew run
```

---

## 🔧 Отличия от оригинала

| Компонент | AIAdvent4Thread | UniversalDevAssistant |
|-----------|----------------|----------------------|
| Язык | JavaScript (Node.js) | Kotlin (JVM) |
| LLM | HuggingFace API | HuggingFace API ✅ |
| Векторизация | Ollama | Ollama ✅ |
| Сервер | Express | Ktor |
| Git интеграция | Нет | Есть (Git MCP) |
| Универсальность | Для Android курса | Для любого проекта ✅ |

---

## 📊 Архитектура интеграции

```
┌─────────────────────────────────────────────┐
│  UniversalDevAssistant (Kotlin)             │
│  ├── HuggingFaceClient ← Qwen2.5-7B         │
│  ├── OllamaClient ← mxbai-embed-large       │
│  ├── RAGService ← Vector + Keyword search   │
│  └── GitMCP ← git команды                   │
└────────────┬────────────────────────────────┘
             │
             ├── HuggingFace API
             │   (ключ из AIAdvent4Thread)
             │
             └── Ollama (localhost:11434)
```

---

## 🎓 Для AIAdvent - День 20

✅ **Интеграция выполнена:**

1. ✅ Модель векторизации из AIAdvent4Thread (Ollama + mxbai-embed-large)
2. ✅ HuggingFace агент из AIAdvent4Thread (Qwen2.5-7B-Instruct)
3. ✅ Ключи из .env файла AIAdvent4Thread

**Дополнительно:**
- ✅ Переписано на Kotlin (типобезопасность, JVM)
- ✅ Добавлен Git MCP
- ✅ REST API на Ktor
- ✅ Универсальность для любого проекта

---

## 🧪 Тестирование

```bash
# 1. Health check
curl http://localhost:3002/health

# 2. Проверка git
curl http://localhost:3002/git/branch

# 3. Вопрос с RAG + векторизацией
curl 'http://localhost:3002/help?q=структура проекта'

# Ответ будет использовать:
# - Ollama для векторного поиска по документации
# - HuggingFace (Qwen) для генерации ответа
# - Git MCP для информации о репозитории
```

---

## 📝 Проверка работы векторизации

Смотрите логи при запуске:

```
✅ Ollama доступна (модель: mxbai-embed-large)
✅ Проиндексировано 5 документов
🔢 Начинаю векторизацию документов через Ollama...
✅ Векторизация завершена (5 векторов)
```

Если Ollama недоступна:

```
⚠️ Ollama недоступна, векторизация будет отключена
⏭️ Продолжаю без векторизации (keyword search)
```

---

**Готово!** Субагент полностью интегрирован с компонентами AIAdvent4Thread! 🎉

