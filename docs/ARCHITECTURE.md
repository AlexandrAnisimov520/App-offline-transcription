# Архитектура

Одно Android-приложение com.bimvo.app: Java UI, MediaRecorder/MediaCodec, локальные PCM 16 kHz, SQLite и последовательный foreground processing service.

Диаризация: Pyannote Segmentation 3 INT8 -> CAM++ zh/en -> sherpa-onnx FastClustering, auto K, threshold 0.7, minDurationOn 0.15 s, minDurationOff 0.3 s. Параметры перенесены из bimvo-diarization (0aba217). Native process выполняется без callback bridge/setConfig; освобождение сессии последовательное. Raw speaker intervals сохраняют overlap.

ASR: русский GigaAM-v3 E2E CTC INT8 через Java ONNX Runtime; английский Parakeet 600M v2 INT8 через sherpa, featureDim=80. AUTO использует Whisper tiny LID; ручной выбор фиксируется для задания. Сохранена проверенная ASR-нарезка и отдельное назначение speaker ID: один смешанный звук не копируется в два текста. Предыдущий speaker-анализ оставлен для совместимости границ ASR.

Qwen3 1.7B GGUF работает через llama.cpp в модуле llm. Нативная библиотека собирается из vendored исходников. Java ORT 1.24.3 сосуществует со статическим sherpa AAR 2.0.1; поддержаны arm64-v8a и x86_64.

SQLite schema 4 мигрирует старые записи, временный план и опубликованный результат разделены. Изменения нового результата публикуются транзакционно. JSON transcript и raw speaker intervals экспортируются отдельно.

Источники: codex/parakeet-600m a97d0c8; codex/frontend-20261108 37dd3cb; bimvo-diarization 0aba217. Структура main сохранена; код адаптирован в src/android вместо механического размещения двух приложений.
