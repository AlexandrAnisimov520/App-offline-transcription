# Форматы данных

Аудио декодируется локально в mono PCM16 16 kHz. Интервалы внутри обработки задаются в samples, экспорт — в целых миллисекундах.

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день"}]}
```

speaker_id стабилен внутри результата записи и не является именем человека. speaker_unknown означает неопределённое назначение. Отдельный экспорт «Голоса JSON» содержит speaker_intervals, start_sample/end_sample, start_ms/end_ms, sample_rate и audio_sha256; одновременные интервалы допустимы. TXT/SRT сохраняют текст, говорящих и время.
