# BIMVO · локальная диаризация

Вход — WAV/FLAC и один источник границ речи. Для готовых интервалов используйте `--segments` с CSV `start,end,overlap`. Время задаётся в секундах от начала записи. Передавайте отдельные речевые реплики, по возможности разделённые в точках смены говорящего. Строки с `overlap=1` отмечают одновременную речь. Если у вас есть локальный ONNX Pyannote segmentation-3.0 в формате Sherpa-ONNX, используйте `--pyannote путь/segmentation.onnx`. Этот режим выделяет активность речи, смену локальных голосовых дорожек и наложения. Глобальные метки вычисляет ERes2NetV2 с кластеризацией. Веса Pyannote в итоговый ZIP не входят. [Формат модели сегментации](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html)

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe pipeline.py --audio audio/session.wav --segments segments/session.csv --output reports/session.csv
.\.venv\Scripts\python.exe pipeline.py --audio audio/session.wav --pyannote path/to/segmentation.onnx --output reports/session.csv
.\.venv\Scripts\python.exe score.py --reference references/session.csv --prediction reports/session.csv
```

CSV результата содержит интервалы, метки `Speaker N` или `UNKNOWN`, причину назначения и, для коротких реплик с эмбеддингом, максимальное сходство. Номера спикеров локальны для записи и упорядочены по первому появлению. Путь к FP32-кандидату можно указать через `--model models/speaker_embedding_v2_optimized.onnx`. Параметр `--ort-opt all` применяет оптимизацию во время загрузки на текущем CPU.

## Обработка записи

- Границы переводятся в индексы сэмплов. Точные дубли схлопываются. Почти совпадающие чистые окна с IoU ≥ 0,8 объединяются относительно исходного окна. Пересечения остальных окон и участки с `overlap=1` исключаются из извлечения признаков. На каждом сэмпле действует не более одного окна. При истинном наложении модель не разделяет голоса, поэтому такие участки выводятся как `UNKNOWN`.
- Чистые реплики длиннее 4 с разбиваются на сопоставимые по длительности окна. Окна от 250 мс подаются в одну сессию ERes2NetV2. Окна от 1,2 с формируют кластеры с помощью complete linkage и косинусного расстояния 0,32. Между окнами может пройти сколько угодно времени. Длительность окна влияет на его вес в центроиде, но не на порог слияния.
- Короткое окно получает существующую метку при сходстве ≥ 0,45 и отрыве ≥ 0,05 от ближайшего конкурента. Короткое окно не порождает кластер. Когда в записи нет чистого окна от 1,2 с, результатом будет `UNKNOWN`. Для смешанной речи внутри длинного окна качество зависит от точности входных границ. Иерархическая кластеризация хранит попарные расстояния и требует O(N²) памяти от числа надёжных окон.

## Повторение замеров

Измерения и разметка описаны в [отчёте](reports/RESULTS.md). Для подготовки записей нужен архив `dev-clean-2.tar.gz` из Mini LibriSpeech. Готовые контролируемые записи, CSV и источники фрагментов вложены в `runs/controlled`. Извлечение признаков, диаризация и проверка модели выполняются локально. [Mini LibriSpeech](https://www.openslr.org/31/)

```powershell
python -m pip install -r requirements-benchmark.txt
python -m pip install torch==2.7.1 torchaudio==2.7.1 --index-url https://download.pytorch.org/whl/cpu
# Необязательно, чтобы пересобрать вложенные записи из Mini LibriSpeech.
python make_controlled.py runs/dev-clean-2.tar.gz
python evaluate.py validation
python model_benchmark.py export runs/validation_features.npz
python evaluate.py test --baseline original
python check_features_parity.py
python model_benchmark.py benchmark runs/test_features.npz runs/benchmark_features.npz
python make_android_fixture.py
```

Для прогона неизменённого фронтенда из исходного ZIP установите совместимые версии PyTorch и TorchAudio и передайте `--baseline original`. Режим `shared-fbank` запускает исходную логику окон и кластеризации с тем же Kaldi fbank, что использует новый пайплайн.

[Инструкция для Android ARM](ANDROID.md)
