"""Check production Java feature/clustering code against the archive's Python reference.

DER here uses prepared boundaries; it does not measure the application's automatic VAD.
"""
import argparse,csv,json,subprocess,sys,tempfile,wave
from pathlib import Path
import numpy as np
import onnxruntime as ort

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--classpath',required=True)
    p.add_argument('--distance',type=float,default=.52)
    p.add_argument('--benchmark',type=Path,required=True);p.add_argument('--report',type=Path,required=True);args=p.parse_args()
    bench=args.benchmark;sys.path.insert(0,str(bench))
    from intervals import prepare_turns
    from clustering import Segment,global_cluster
    from score import read,score
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    session=ort.InferenceSession(str(bench/'models/speaker_embedding_v2_basic.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
    report=[]
    def java(*rest): subprocess.run([args.java,'-cp',args.classpath,'com.bimvo.app.SpeakerBenchmarkDriver',*map(str,rest)],check=True)
    for scenario in ['clean','overlap']:
        base=bench/'runs/controlled/test'/scenario
        with tempfile.TemporaryDirectory(prefix='bimvo-speaker-') as temporary:
            tmp=Path(temporary)
            with wave.open(str(base/'audio.wav'),'rb') as audio:
                assert audio.getframerate()==16000 and audio.getnchannels()==1 and audio.getsampwidth()==2
                count=audio.getnframes();(tmp/'audio.pcm').write_bytes(audio.readframes(count))
            with (base/'segments.csv').open(encoding='utf-8-sig',newline='') as f: turns=list(prepare_turns(list(csv.DictReader(f)),count,4.0))
            with (tmp/'turns.csv').open('w',newline='') as f:
                writer=csv.writer(f);writer.writerow(['start','end','overlap']);writer.writerows((t.start,t.end,int(t.overlap)) for t in turns)
            java('features',tmp/'audio.pcm',tmp/'turns.csv',tmp/'features')
            with (tmp/'features/features.csv').open() as f: rows=list(csv.DictReader(f))
            segments=[];vectors=[]
            for turn,row in zip(turns,rows):
                frames=int(row['frames']);embedding=None
                if frames:
                    feature=np.fromfile(tmp/'features'/row['file'],dtype='<f4').reshape(1,frames,80)
                    embedding=session.run(['embedding'],{'x':feature})[0][0]
                segments.append(Segment(turn.start/16000,turn.end/16000,embedding,turn.overlap))
                vectors.append(','.join([str(turn.start),str(turn.end)]+([] if embedding is None else [str(float(v)) for v in embedding])))
            (tmp/'embeddings.csv').write_text('\n'.join(vectors))
            java('cluster',tmp/'embeddings.csv',tmp/'labels.txt')
            actual=list(map(int,(tmp/'labels.txt').read_text().split()));expected,_=global_cluster(segments,max_cosine_distance=args.distance)
            assert actual==expected,(scenario,actual,expected)
            prediction=[(t.start/16000,t.end/16000,f'Speaker {label}' if label else 'UNKNOWN') for t,label in zip(turns,actual)]
            metrics=score(read(base/'reference.csv'),prediction)
            entry={'scenario':scenario,'distance_threshold':args.distance,'prepared_boundaries':True,'windows':len(turns),'java_python_labels_identical':True,'speakers':len(set(actual)-{0}),'unknown':actual.count(0),'der':metrics['der']}
            report.append(entry);print(json.dumps(entry,ensure_ascii=False),flush=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__': main()
