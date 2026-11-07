# Сравнение диаризации: текущая и экспериментальная связки

Same official AMI manual v1.6.2 references per audio extent, zero collar, overlap included, optimal global mapping. Primary: transcriber segments. Secondary: lexical word intervals. No oracle speaker count. New fast-clustering .85 was fixed before both runs.
Full ES2002a, 1272.64 seconds. Fixed first 300 seconds of TS3004a; no retuning on TS3004a. No Android performance or on-device execution test.

| Аудио | Вариант | DER ↓ | JER ↓ | Голосов | Recall речи ↑ |
|---|---|---:|---:|---:|---:|
| ES2002a_full | current_app | 85.54% | 81.55% | 37/4 | 84.02% |
| ES2002a_full | embedding_only | 91.55% | 86.56% | 93/4 | 84.02% |
| ES2002a_full | new_pipeline | 45.26% | 45.57% | 45/4 | 92.18% |
| TS3004a_first300s | current_app | 67.45% | 74.00% | 14/4 | 83.40% |
| TS3004a_first300s | new_pipeline | 32.79% | 59.36% | 16/4 | 89.52% |

current_app: Silero VAD → двухсекундные окна → ERes2NetV2 zh-cn → complete linkage 0,52.
embedding_only: те же VAD, окна, признаки и кластеризация 0,52; изменены только веса на ERes2Net VoxCeleb.
new_pipeline: pyannote segmentation → маскирование/эмбеддинги ERes2Net VoxCeleb → FastClustering 0,85; число голосов автоматически (-1), min_duration_on/off=0,1, window_shift_ratio=0,1.
Полный пайплайн меняет несколько этапов. Его улучшение нельзя целиком приписывать одной новой модели.
JER нужно смотреть вместе с DER: лишние несопоставленные гипотезы отдельно не штрафуются в JER. Recall речи оценивается по объединению речевых интервалов; overlap — отдельная метрика.
Настройки не подбирались на TS3004a. Проверено лишь начало этой встречи, не вся встреча и не весь AMI.
Тесты выполнены на CPU компьютера. В приложение эксперимент не подключён, APK не пересобирался.
Веса не дообучались. Улучшение на этих записях не гарантирует правильность каждого голоса на новых данных.

Источники: AMI Consortium, AMI Meeting Corpus, CC BY 4.0; ручные аннотации v1.6.2.
https://groups.inf.ed.ac.uk/ami/download/
https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/offline-speaker-diarization.py

Повторить сводку: python -X utf8 ./qa/summarize_diarization_comparison.py

Вывод по текущему сравнению: полный новый конвейер даёт меньший DER и JER на обоих проверенных аудио, но всё ещё создаёт лишние группы. Простая замена весов при старом пороге/старых окнах ухудшила DER. Нельзя утверждать, что одна новая модель сама по себе лучше, или что проблема диаризации исправлена. Для дальнейшей настройки требуется отдельная валидация, затем новая независимая проверка; TS3004a не использовалась для подбора параметров этого прогона.

Сырые прогнозы и отчёты: ES2002a/prediction.csv, ES2002a/report.json, TS3004a_first300s/prediction.csv, TS3004a_first300s/report.json. Абляция — ES2002a_embedding_only/. Для повторного запуска используйте qa/eval_sherpa_diarization.py и qa/eval_embedding_ablation.py; абсолютные пути моделей и хеши записаны в report.json. Все времена получены на CPU компьютера при параллельных экспериментах, поэтому не являются сравнением скорости на телефоне.
