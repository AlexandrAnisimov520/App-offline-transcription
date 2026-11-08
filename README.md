# BIMVO — локальная расшифровка для Android

Единый актуальный проект находится в **src/android**. Структура сдачи сохранена: исходники — src, конфигурация — config, описание — docs, материалы — artifacts.

В одной кодовой базе объединены фронт codex/frontend-20261108, русское GigaAM-v3 и английское Parakeet 600M INT8 из codex/parakeet-600m, CAM++/Pyannote-диаризация с параметрами bimvo-diarization, Qwen3 и локальная SQLite-библиотека. Ветка main содержит одно приложение, а не две параллельные папки проектов.

Язык новых записей: Автоматически / Русский / English. После записи/импорта обработка начинается автоматически. Есть индикатор и волны записи, перемотка и карта, сортировка, переименование, удаление и экспорт. Обработка выполняется на устройстве без облачных API.

- [Установка и использование](docs/INSTALLATION.md)
- [Архитектура и модели](docs/ARCHITECTURE.md)
- [Ограничения](docs/LIMITATIONS.md)
- [Результаты проверок](docs/TEST_RESULTS.md)
- [JSON и временные метки](docs/PROTOCOL.md)
- [Материалы APK](artifacts/apk/README.md)
- [Демонстрация](artifacts/demo/README.md)

## Подготовка сборки

Открыть src/android в Android Studio (JDK 21, SDK 35, NDK 28.2.13676358, CMake 3.31.6). Веса моделей и AAR не хранятся в Git. Их можно перенести из рабочего offline bundle:

```powershell
python src/android/scripts/stage_models.py --models-dir "D:/bundle/models" --cam "D:/bundle/campplus_zh_en.onnx"
```

Также поддерживается --apk для извлечения models/ из уже имеющегося BIMVO APK. CAM++ указывается отдельно, если его нет в том APK. Версии и проверки моделей — config/models.json и app/build.gradle. ASR/LID/runtime подготавливаются Gradle на компьютере; установленное приложение ничего не скачивает.

```powershell
cd src/android
.\gradlew.bat :app:assembleDebug
```

APK: src/android/app/build/outputs/apk/debug/app-debug.apk. Для передачи большого APK используйте ссылку на файл, а не обычное почтовое вложение.
