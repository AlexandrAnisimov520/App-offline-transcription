"""Compare saved predictions under one overlap-aware protocol; never tune predictions."""
import csv
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'speaker_benchmark'))
from score import read, score
from diarization_diagnostics import diagnostics


def production(audit):
    case = json.loads((audit / 'cases.json').read_text(encoding='utf-8'))[0]
    return [(w['start'], w['end'], w['speaker'] if w['speaker'] != 'speaker_unknown' else 'UNKNOWN')
            for w in case['configs'][0]['windows']]


def main():
    out = ROOT / 'diarization_v3'
    entries = []
    scenarios = [('ES2002a_full', ROOT / 'ami_es2002a_audit', out / 'ES2002a'),
                 ('TS3004a_first300s', out / 'TS3004a_old_first300s', out / 'TS3004a_first300s')]
    for scenario, baseline, candidate in scenarios:
        if not (candidate / 'report.json').is_file():
            raise RuntimeError('Evaluation still incomplete: ' + scenario)
        variants = [('current_app', production(baseline)), ('new_pipeline', read(candidate / 'prediction.csv'))]
        ablation = out / 'ES2002a_embedding_only'
        if scenario == 'ES2002a_full' and (ablation / 'report.json').is_file():
            variants.insert(1, ('embedding_only', read(ablation / 'prediction.csv')))
        for name, prediction in variants:
            for kind in ['segments', 'words']:
                reference = read(baseline / f'reference_{kind}.csv')
                metrics = score(reference, prediction)
                extra = diagnostics(reference, prediction)
                entries.append({'scenario': scenario, 'variant': name, 'reference': kind, **metrics, **extra})
    report = {'protocol': 'Same official AMI manual v1.6.2 references per audio extent, zero collar, overlap included, '
                          'optimal global mapping. Primary: transcriber segments. Secondary: lexical word intervals. '
                          'No oracle speaker count. New fast-clustering .85 was fixed before both runs.',
              'scope': 'Full ES2002a, 1272.64 seconds. Fixed first 300 seconds of TS3004a; '
                       'no retuning on TS3004a. No Android performance or on-device execution test.',
              'results': entries}
    (out / 'COMPARISON.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    columns = ['scenario', 'variant', 'reference', 'der', 'jer', 'reference_speakers', 'predicted_speakers',
               'miss_seconds', 'false_alarm_seconds', 'confusion_seconds', 'vad_precision', 'vad_recall', 'vad_f1',
               'overlap_recall', 'short_correct', 'short_total', 'return_correct', 'return_total']
    with (out / 'COMPARISON.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, extrasaction='ignore')
        writer.writeheader(); writer.writerows(entries)
    lines = ['# Сравнение диаризации: текущая и экспериментальная связки', '',
             report['protocol'], report['scope'], '',
             '| Аудио | Вариант | DER ↓ | JER ↓ | Голосов | Recall речи ↑ |',
             '|---|---|---:|---:|---:|---:|']
    for e in entries:
        if e['reference'] != 'segments': continue
        lines.append(f"| {e['scenario']} | {e['variant']} | {e['der']:.2%} | {e['jer']:.2%} | "
                     f"{e['predicted_speakers']}/{e['reference_speakers']} | {e['vad_recall']:.2%} |")
        print(e['scenario'], e['variant'], f"DER={e['der']:.2%}", f"JER={e['jer']:.2%}",
              'speakers', e['predicted_speakers'], '/', e['reference_speakers'])
    lines += ['', 'current_app: Silero VAD → двухсекундные окна → ERes2NetV2 zh-cn → complete linkage 0,52.',
              'embedding_only: те же VAD, окна, признаки и кластеризация 0,52; изменены только веса на ERes2Net VoxCeleb.',
              'new_pipeline: pyannote segmentation → маскирование/эмбеддинги ERes2Net VoxCeleb → FastClustering 0,85; '
              'число голосов автоматически (-1), min_duration_on/off=0,1, window_shift_ratio=0,1.',
              'Полный пайплайн меняет несколько этапов. Его улучшение нельзя целиком приписывать одной новой модели.',
              'JER нужно смотреть вместе с DER: лишние несопоставленные гипотезы отдельно не штрафуются в JER. '
              'Recall речи оценивается по объединению речевых интервалов; overlap — отдельная метрика.',
              'Настройки не подбирались на TS3004a. Проверено лишь начало этой встречи, не вся встреча и не весь AMI.',
              'Тесты выполнены на CPU компьютера. В приложение эксперимент не подключён, APK не пересобирался.',
              'Веса не дообучались. Улучшение на этих записях не гарантирует правильность каждого голоса на новых данных.',
              '', 'Источники: AMI Consortium, AMI Meeting Corpus, CC BY 4.0; ручные аннотации v1.6.2.',
              'https://groups.inf.ed.ac.uk/ami/download/',
              'https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/offline-speaker-diarization.py',
              '', 'Повторить сводку: python -X utf8 ./qa/summarize_diarization_comparison.py']
    (out / 'REPORT_RU.md').write_text('\n'.join(lines), encoding='utf-8')


if __name__ == '__main__':
    main()
