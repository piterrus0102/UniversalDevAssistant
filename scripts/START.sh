#!/bin/bash

# Скрипт быстрого запуска Universal Dev Assistant

# Переходим в корень проекта (на уровень выше scripts/)
cd "$(dirname "$0")/.." || exit 1

echo "🤖 Universal Dev Assistant - Quick Start"
echo "========================================="
echo ""

# Проверка Java
if ! command -v java &> /dev/null; then
    echo "❌ Java не найдена. Установите JDK 17+:"
    echo "   brew install openjdk@17"
    exit 1
fi

echo "✅ Java: $(java -version 2>&1 | head -n 1)"
echo ""

# Проверка config.yaml
if [ ! -f "config.yaml" ]; then
    echo "❌ config.yaml не найден"
    echo "   Создайте из примера: cp config.yaml.example config.yaml"
    exit 1
fi

echo "✅ config.yaml найден"
echo ""

# Проверка Ollama (опционально)
if command -v ollama &> /dev/null; then
    echo "✅ Ollama установлена"
    
    # Проверяем, запущена ли Ollama
    if curl -s http://localhost:11434 > /dev/null 2>&1; then
        echo "✅ Ollama работает"
    else
        echo "⚠️  Ollama не запущена. Запустите в другом терминале:"
        echo "   ollama serve"
        echo ""
        echo "   Векторизация будет отключена (keyword search)"
    fi
else
    echo "⚠️  Ollama не установлена (опционально)"
    echo "   Установка: brew install ollama"
    echo "   Векторизация будет отключена (keyword search)"
fi

echo ""
echo "🔍 Проверка занятых портов..."

# Получаем порт из config.yaml (по умолчанию 3002)
PORT=$(grep "port:" config.yaml 2>/dev/null | awk '{print $2}' | tr -d '\r')
if [ -z "$PORT" ]; then
    PORT=3002
fi

echo "   Порт из config.yaml: $PORT"

# Проверяем занят ли порт
if lsof -ti :$PORT > /dev/null 2>&1; then
    echo "⚠️  Порт $PORT занят. Останавливаем процесс..."
    
    # Убиваем процесс на порту
    lsof -ti :$PORT | xargs kill -9 2>/dev/null
    
    # Ждем немного
    sleep 2
    
    # Проверяем еще раз
    if lsof -ti :$PORT > /dev/null 2>&1; then
        echo "❌ Не удалось освободить порт $PORT"
        exit 1
    else
        echo "✅ Порт $PORT освобожден"
    fi
else
    echo "✅ Порт $PORT свободен"
fi

echo ""
echo "🚀 Запуск сервера..."
echo ""

./gradlew run

