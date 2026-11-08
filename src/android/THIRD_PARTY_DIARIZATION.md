# Происхождение компонентов

Репозиторий исходного проекта (GitHub ID 1449457273): https://api.github.com/repositories/1449457273
Commit: c773631c4933a709597f0a61d5e9c6ad875991ab

SpeakerClustering.kt, Words.kt, TranscriptAssembler.kt скопированы с адаптацией package и DTO BIMVO; в кластеризацию добавлена отмена. SpeakerEmbedder и VadEngine перенесены из Engines.kt: аудио читается из файла, остальные параметры сохранены. Подготовка PCM повторяет AudioDecoder.normalize с сохранением результата в PCM16. Порядок стадий основан на TranscriptionPipeline.kt.

Оригинальные файлы для сравнения: third_party/diarization. Автор исходного проекта: maxtaran2010; авторство за BIMVO не заявляется.

CAM++ / 3D-Speaker: https://github.com/modelscope/3D-Speaker
Sherpa-ONNX: https://github.com/k2-fsa/sherpa-onnx
Silero VAD: https://github.com/snakers4/silero-vad

Фактически скачанные веса и AAR проверяются SHA-256 в app/build.gradle. Архив не меняет лицензии сторонних моделей и библиотек.
