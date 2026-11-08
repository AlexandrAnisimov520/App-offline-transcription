"""Reproducible, local audit of BIMVO production speaker pipeline. No threshold tuning or reference-label fabrication.

AAC is decoded by FFmpeg; production Java performs downmix/resampling, fbank, window geometry,
and clustering. Python ORT executes the same verified ONNX graphs on CPU.
"""
import re
import argparse,base64,csv,hashlib,html,io,json,math,platform,subprocess,sys,tempfile,time,wave,zipfile
from pathlib import Path
import numpy as np
import onnxruntime as ort

NAMES=['clean_01','clean_02','clean_03','clean_04','dialogue_01','dialogue_02','dialogue_03','dialogue_04','terms_02','terms_03']
VALIDATION={'clean_01','clean_03','dialogue_01','dialogue_03','terms_02'}
PROJECT=Path(__file__).resolve().parents[1]

def dump(path,data):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
def table(path,rows,columns):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('w',newline='',encoding='utf-8-sig') as f:
        writer=csv.DictWriter(f,fieldnames=columns,extrasaction='ignore');writer.writeheader();writer.writerows(rows)
def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def vad_replay(audio,session):
    state=np.zeros((2,1,128),np.float32);context=np.zeros(64,np.float32);speech=[];frames=[];start=pending=-1;total=len(audio)
    def append(a,b):
        if b-a<1600:return
        a=max(0,a-1280);b=min(total,b+1280)
        if speech and a<=speech[-1][1]:speech[-1][1]=b
        else:speech.append([a,b])
    for cursor in range(0,total,512):
        got=min(512,total-cursor);signal=np.zeros(576,np.float32);signal[:64]=context;signal[64:64+got]=audio[cursor:cursor+got]
        result=session.run(['output','stateN'],{'input':signal[None,:],'state':state,'sr':np.array(16000,np.int64)})
        probability=float(result[0].reshape(-1)[0]);state=result[1];context=signal[512:].copy()
        frames.append({'start':cursor/16000,'end':(cursor+got)/16000,'probability':probability})
        if start<0 and probability>=.5:start=cursor
        if start>=0:
            if probability<.35:
                if pending<0:pending=cursor
                if cursor+got-pending>=3200:append(start,pending);start=pending=-1
            else:pending=-1
    if start>=0:append(start,pending if pending>=0 else total)
    return speech,frames
def windows_for(speech,width,hop,disjoint=False):
    result=[]
    for a,b in speech:
        if b-a<320:continue
        if disjoint:
            count=math.ceil((b-a)/width)
            for i in range(count):
                left=a+(b-a)*i//count;right=a+(b-a)*(i+1)//count
                result.append([len(result),left,right,left,right])
            continue
        length=min(width,b-a);local=[];start=a
        while True:
            local.append([start,start+length])
            if start+length==b:break
            start=min(b-length,start+hop)
        for i,(start,end) in enumerate(local):
            left=a if i==0 else (sum(local[i-1])+start+end)//4
            right=b if i==len(local)-1 else (start+end+sum(local[i+1]))//4
            result.append([len(result),start,end,left,right])
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--audio-dir',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--ffmpeg',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--android-jar',type=Path,required=True);p.add_argument('--ort-java',type=Path,required=True)
    p.add_argument('--names',nargs='+',default=NAMES)
    p.add_argument('--counts',type=Path)
    p.add_argument('--audio-extension',default='.m4a')
    p.add_argument('--seconds',type=float)
    p.add_argument('--skip-review',action='store_true')
    p.add_argument('--baseline-only',action='store_true')
    p.add_argument('--normalize-quiet-vad',action='store_true')
    args=p.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    counts=json.loads(args.counts.read_text(encoding='utf-8-sig')) if args.counts else {}

    source=PROJECT/'app/src/main/java/com/bimvo/app';models=PROJECT/'app/src/main/assets/models'
    threshold=float(re.search(r'DISTANCE\s*=\s*([.0-9]+)',(source/'SpeakerClustering.java').read_text(encoding='utf-8')).group(1))
    classes=out/'java_classes';classes.mkdir(exist_ok=True)
    java=str(args.java_home/'bin/java.exe');javac=str(args.java_home/'bin/javac.exe')
    classpath=';'.join(map(str,[classes,PROJECT/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes',args.android_jar,args.ort_java]))
    compile_sources=[source/(name+'.java') for name in ['PcmResampler','SpeakerFbank','SpeakerClustering','SpeakerDiarization','SpeakerEmbedding','SileroVad','ChunkPlanner','VadInputGain']]
    bridges=[PROJECT/'qa/java/com/bimvo/app'/name for name in ['SpeakerAuditBridge.java','SpeakerBenchmarkDriver.java']]
    subprocess.run([javac,'-encoding','UTF-8','--release','17','-cp',classpath,'-d',str(classes),*map(str,compile_sources+bridges)],check=True)
    def call(cls,*rest):subprocess.run([java,'-cp',classpath,'com.bimvo.app.'+cls,*map(str,rest)],check=True)
    sys.path.insert(0,str(PROJECT.parent/'speaker_benchmark'))
    from clustering import Segment,global_cluster
    class SampleSegment(Segment):
        @property
        def duration(self):
            # Java uses integer PCM samples: avoid 1.2 becoming 1.1999999999999993.
            return round((self.end-self.start)*16000)/16000

    vad_options=ort.SessionOptions();vad_options.intra_op_num_threads=1;vad_options.inter_op_num_threads=1
    embed_options=ort.SessionOptions();embed_options.intra_op_num_threads=2;embed_options.inter_op_num_threads=1;embed_options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    vad=ort.InferenceSession(str(models/'silero_vad.onnx'),sess_options=vad_options,providers=['CPUExecutionProvider'])
    embedding=ort.InferenceSession(str(models/'speaker_embedding_v2_basic.onnx'),sess_options=embed_options,providers=['CPUExecutionProvider'])
    manifest={'created':'2026-11-07','backend':'FFmpeg AAC decoder + production Java preprocessing/windows/clustering + Python ORT CPU','python':sys.version,'ort':ort.__version__,'numpy':np.__version__,'platform':platform.platform(),'models':{x:sha(models/x) for x in ['silero_vad.onnx','speaker_embedding_v2_basic.onnx']},'source_sha256':{x.name:sha(x) for x in compile_sources},'reference_policy':'Counts confirmed by user; no timed gold labels supplied for these ten files. DER unavailable until independently verified timing.','files':[]}
    texts={}
    for name in ['BIMVO_transcripts_20.zip','BIMVO_diarization_4_references.zip']:
        path=args.audio_dir/name
        if not path.exists():continue
        destination=out/'references'/('texts' if 'transcripts' in name else 'unmatched_diarization_4');destination.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(path) as archive:
            for item in archive.infolist():
                if item.is_dir():continue
                safe=Path(item.filename).name
                data=archive.read(item);(destination/safe).write_bytes(data)
                if 'transcripts' in name and safe.endswith('.txt'):texts[Path(safe).stem]=data.decode('utf-8-sig')
    script_dir=out/'references/unmatched_diarization_4'
    for path in script_dir.glob('diarization_??_speakers.csv'):
        name=path.stem.removesuffix('_speakers')
        if name not in args.names:continue
        with path.open(encoding='utf-8-sig',newline='') as stream:
            turns=list(csv.DictReader(stream))
        texts[name]='\n'.join(r['speaker_id']+': '+r['text'] for r in turns if r.get('speaker_id'))
    aggregate=[];review=[]
    for name in args.names:
        started=time.perf_counter();folder=out/name;folder.mkdir(exist_ok=True);original=args.audio_dir/(name+args.audio_extension)
        metadata=json.loads(subprocess.check_output([str(args.ffmpeg.with_name('ffprobe.exe')),'-v','error','-show_entries','format=duration:stream=sample_rate,channels,codec_name','-of','json',str(original)],text=True))
        info=metadata['streams'][0];rate=int(info['sample_rate']);channels=int(info['channels']);expected=int(counts[name]['count']) if name in counts else (2 if name.startswith('dialogue') else 1)
        with tempfile.TemporaryDirectory(prefix='decode-',dir=folder) as temporary:
            native=Path(temporary)/'native.pcm'
            subprocess.run([str(args.ffmpeg),'-v','error','-y','-i',str(original),*(['-t',str(args.seconds)] if args.seconds else []),'-map','0:a:0','-vn','-c:a','pcm_s16le','-f','s16le',str(native)],check=True)
            stereo=np.fromfile(native,dtype='<i2').reshape(-1,channels).astype(np.float32)/32768
            channel_rms=np.sqrt(np.mean(stereo.astype(np.float64)**2,axis=0));corr=float(np.corrcoef(stereo.T)[0,1]) if channels==2 else None
            quality={'source_rate':rate,'channels':channels,'channel_rms':channel_rms.tolist(),'channel_correlation':corr,'source_clipped_fraction':float(np.mean(np.abs(stereo)>.999))}
            call('SpeakerAuditBridge','resample',native,folder/'audio.pcm',rate,channels)
        raw=(folder/'audio.pcm').read_bytes();audio=np.frombuffer(raw,dtype='<i2').astype(np.float32)/32768
        with wave.open(str(folder/'audio.wav'),'wb') as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(16000);f.writeframes(raw)
        input_gain=float(subprocess.check_output([java,'-cp',classpath,'com.bimvo.app.SpeakerAuditBridge','gain',str(folder/'audio.pcm')],text=True)) if args.normalize_quiet_vad else 1.0
        quality['vad_input_gain']=input_gain
        speech,vad_frames=vad_replay(audio*input_gain,vad);table(folder/'vad_frames.csv',vad_frames,['start','end','probability'])
        table(folder/'speech_samples.csv',[{'start':a,'end':b} for a,b in speech],['start','end'])
        call('SpeakerAuditBridge','windows',folder/'speech_samples.csv',folder/'baseline_windows.csv')
        with (folder/'baseline_windows.csv').open() as f:baseline=[[int(row[k]) for k in ['index','start','end','left','right']] for row in csv.DictReader(f)]
        assert baseline==windows_for(speech,32000,16000),'Host window adapter diverged from Java'
        configurations={'baseline_2s_hop1s':baseline}
        if name in VALIDATION and not args.baseline_only:
            configurations.update({'short_1p2s_hop0p6s':windows_for(speech,19200,9600),'long_3s_hop1p5s':windows_for(speech,48000,24000),'disjoint_vad_max4s':windows_for(speech,64000,64000,True)})
        case={'name':name,'source_sha256':sha(original),'expected_speakers':expected,'count_source':counts.get(name,{}).get('source','user_confirmed'),'split':'validation' if name in VALIDATION else 'heldout_baseline_only','duration_seconds':len(audio)/16000,'source_metadata':metadata,'audio_quality':quality,'transcript':texts.get(name,''),'speech_spans':[[a/16000,b/16000] for a,b in speech],'configs':[]}
        manifest['files'].append({k:case[k] for k in ['name','source_sha256','expected_speakers','count_source','split','duration_seconds']})
        for config,windows in configurations.items():
            target=folder/config;target.mkdir(exist_ok=True)
            table(target/'input_windows.csv',[dict(zip(['index','start','end','left','right'],w)) for w in windows],['index','start','end','left','right'])
            vectors=[];frame_counts=[]
            with tempfile.TemporaryDirectory(prefix='features-',dir=folder) as temporary:
                work=Path(temporary);table(work/'turns.csv',[{'start':w[1],'end':w[2],'overlap':0} for w in windows],['start','end','overlap'])
                call('SpeakerBenchmarkDriver','features',folder/'audio.pcm',work/'turns.csv',work/'features')
                with (work/'features/features.csv').open() as f:feature_rows=list(csv.DictReader(f))
                for row in feature_rows:
                    frames=int(row['frames']);frame_counts.append(frames)
                    if frames:
                        feature=np.fromfile(work/'features'/row['file'],dtype='<f4').reshape(1,frames,80)
                        vector=embedding.run(['embedding'],{'x':feature})[0][0]
                        assert vector.shape==(192,) and np.isfinite(vector).all()
                        vectors.append(vector)
                    else:vectors.append(None)
            raw_vectors=np.stack([v if v is not None else np.full(192,np.nan,np.float32) for v in vectors]) if vectors else np.empty((0,192),np.float32)
            np.save(target/'embeddings_raw.npy',raw_vectors)
            csv_vectors=['{},{},{}'.format(w[1],w[2],','.join(str(float(x)) for x in vector)).rstrip(',') if vector is not None else f'{w[1]},{w[2]}' for w,vector in zip(windows,vectors)]
            (target/'embedding_input.csv').write_text('\n'.join(csv_vectors),encoding='utf-8')
            call('SpeakerBenchmarkDriver','cluster',target/'embedding_input.csv',target/'labels.txt')
            labels=list(map(int,(target/'labels.txt').read_text().split()))
            reference_labels,explanations=global_cluster([SampleSegment(w[1]/16000,w[2]/16000,v) for w,v in zip(windows,vectors)],max_cosine_distance=threshold,max_seeds=512)
            parity=labels==reference_labels
            normalized=np.nan_to_num(raw_vectors)/np.maximum(np.linalg.norm(np.nan_to_num(raw_vectors),axis=1,keepdims=True),1e-10)
            similarity=np.clip(normalized@normalized.T,-1,1);distance=1-similarity;np.save(target/'cosine_distance.npy',distance)
            table(target/'cosine_distance.csv',[{'window':i,**{str(j):float(d) for j,d in enumerate(row)}} for i,row in enumerate(distance)],['window',*map(str,range(len(windows)))])
            rows=[]
            for i,(w,label,vector,explanation) in enumerate(zip(windows,labels,vectors,explanations)):
                signal=audio[w[1]:w[2]];rms=float(np.sqrt(np.mean(signal.astype(np.float64)**2)))
                rows.append({'index':i,'context_start':w[1]/16000,'context_end':w[2]/16000,'start':w[3]/16000,'end':w[4]/16000,'speaker':f'speaker_{label}' if label else 'speaker_unknown','frames':frame_counts[i],'seed_eligible':bool(vector is not None and w[2]-w[1]>=19200),'reason':explanation['reason'],'assignment_similarity':explanation['confidence'],'rms_dbfs':20*math.log10(max(rms,1e-10)),'embedding_norm':float(np.linalg.norm(vector)) if vector is not None else None})
            table(target/'windows.csv',rows,list(rows[0]) if rows else ['index','start','end','speaker'])
            clusters=[]
            for label in sorted(set(labels)-{0}):
                ids=[i for i,x in enumerate(labels) if x==label];within=distance[np.ix_(ids,ids)]
                clusters.append({'speaker':f'speaker_{label}','windows':len(ids),'assigned_seconds':sum((windows[i][4]-windows[i][3])/16000 for i in ids),'max_pairwise_distance':float(within.max()),'window_ids':ids})
            pairs=[]
            for a in range(len(windows)):
                for b in range(a):pairs.append({'a':b,'b':a,'speaker_a':labels[b],'speaker_b':labels[a],'distance':float(distance[a,b]),'same_predicted_cluster':bool(labels[a] and labels[a]==labels[b]),'same_real_speaker_confirmed':True if expected==1 else ''})
            table(target/'pairs.csv',pairs,['a','b','speaker_a','speaker_b','distance','same_predicted_cluster','same_real_speaker_confirmed'])
            cross=[]
            for a in range(len(clusters)):
                for b in range(a):
                    d=distance[np.ix_(clusters[a]['window_ids'],clusters[b]['window_ids'])]
                    cross.append({'a':clusters[b]['speaker'],'b':clusters[a]['speaker'],'min_distance':float(d.min()),'max_distance':float(d.max()),'mean_distance':float(d.mean()),'distance_threshold':threshold,'closest_pair_passes_threshold':bool(d.min()<=threshold),'complete_link_rejects':bool(d.max()>threshold)})
            dump(target/'clusters.json',{'clusters':clusters,'cross_cluster_distances':cross})
            assigned=sum((w[4]-w[3])/16000 for w in windows);unknown=sum((w[4]-w[3])/16000 for w,l in zip(windows,labels) if not l)
            boundaries=[{'left_window':i-1,'right_window':i,'time':w[3]/16000,'left_speaker':labels[i-1],'right_speaker':labels[i],'adjacent_distance':float(distance[i-1,i])} for i,w in enumerate(windows) if i and labels[i]!=labels[i-1] and w[3]<=windows[i-1][4]+4000]
            dump(target/'boundaries_for_review.json',sorted(boundaries,key=lambda r:r['adjacent_distance'],reverse=True))
            summary={'file':name,'split':case['split'],'configuration':config,'expected_speakers':expected,'found_speakers':len(clusters),'extra_clusters':max(0,len(clusters)-expected),'missing_clusters':max(0,expected-len(clusters)),'count_matches':len(clusters)==expected,'windows':len(windows),'singleton_clusters':sum(x['windows']==1 for x in clusters),'unknown_fraction':unknown/max(assigned,1e-9),'java_python_parity':parity,'distance_threshold':threshold,'der':None,'reference_status':case['count_source']+'; turn timing pending'}
            aggregate.append(summary);dump(target/'summary.json',summary);case['configs'].append({'summary':summary,'windows':rows,'boundaries':boundaries,'clusters':clusters})
        case['elapsed_host_seconds']=time.perf_counter()-started
        dump(folder/'case.json',case);review.append(case)
        print(json.dumps(case['configs'][0]['summary'],ensure_ascii=False),flush=True)
        dump(out/'manifest.json',manifest);dump(out/'cases.json',review);table(out/'summary.csv',aggregate,list(aggregate[0]))
    dump(out/'summary.json',aggregate)
    if not args.skip_review:build_review(out,review)
    print('AUDIT COMPLETE:',out,flush=True)

def build_review(out,cases):
    # The independent annotation UI is intentionally blank: model predictions are never prefilled as gold.
    data=[]
    for case in cases:
        name=case['name'];audio=base64.b64encode((out/name/'audio.wav').read_bytes()).decode()
        text=case['transcript'];turns=[]
        for line in text.splitlines():
            if ':' in line and line.startswith('Speaker '):
                speaker,words=line.split(':',1);turns.append({'speaker':speaker.replace('Speaker ','speaker_'),'text':words.strip()})
        if not turns:turns=[{'speaker':'speaker_1','text':text}]
        data.append({'name':name,'duration':case['duration_seconds'],'expected':case['expected_speakers'],'sha':case['source_sha256'],'audio':audio,'turns':turns,'prediction':case['configs'][0]['windows'],'summary':case['configs'][0]['summary']})
    payload=json.dumps(data,ensure_ascii=False).replace('</','<\\/')
    page=r'''<!doctype html><html lang="ru"><meta charset="utf-8"><title>BIMVO · проверка говорящих</title>
<style>body{background:#0b0c11;color:#f3f2f3;font:16px system-ui;margin:0}main{max-width:1050px;margin:auto;padding:28px}h1{font-size:28px}p{color:#aaa;line-height:1.5}select,input,button{font:inherit;color:inherit;background:#181922;border:1px solid #424351;border-radius:8px;padding:9px}button{cursor:pointer}button:hover{border-color:#bca9df}audio{width:100%;margin:18px 0}.card{background:#15161d;border:1px solid #323340;border-radius:12px;padding:18px;margin:14px 0}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.turn{border-top:1px solid #333;padding:14px 0}.turn input[type=number]{width:95px}.words{margin:10px 0;line-height:1.5}.warn{color:#e4bf88}.tag{color:#6eb6b3}.small{font-size:13px}#status{white-space:pre-wrap}details{margin-top:20px}table{border-collapse:collapse;width:100%}td,th{padding:7px;border-bottom:1px solid #333;text-align:left}</style>
<main><h1>BIMVO · независимая разметка и проверка окон</h1><p>Число людей подтверждено пользователем. Начало и конец реплик ещё нужно отметить по аудио. Метки модели не являются эталоном. Для точного DER размечайте участки речи, исключая длинные паузы. Время — секунды от начала файла; пересечения допустимы только для реальной одновременной речи.</p>
<div class="row"><select id="files"></select><button onclick="saveDraft()">Сохранить черновик JSON</button><button onclick="exportCsv()">Экспорт проверенного CSV</button><label>Загрузить черновик <input type="file" id="load" accept=".json"></label></div>
<div class="card"><h2 id="title"></h2><div class="tag" id="facts"></div><audio id="player" controls></audio><div class="row"><button onclick="jump(-2)">−2 сек</button><button onclick="jump(2)">+2 сек</button><label>Скорость <select onchange="player.playbackRate=+this.value"><option>1</option><option>0.75</option><option>0.5</option><option>1.25</option></select></label><span id="clock"></span></div></div>
<div class="card"><h2>Эталон — подтвердить прослушиванием</h2><div class="warn small">Поля времени не заполнены автоматически. Даже если текст содержит Speaker 1/2, проверьте соответствие реальным голосам.</div><div id="turns"></div><button onclick="addTurn()">Добавить речевой участок</button><p id="status"></p></div>
<details class="card"><summary>Диагностика модели — отдельно от эталона</summary><p>Откройте после независимой разметки, чтобы не подстраивать эталон под модель. Нажатие на окно воспроизводит его аудиоконтекст; контекст и участок ответственности имеют разные границы.</p><div id="model"></div></details></main>
<script>const DATA=__DATA__;let selected=0;const drafts=DATA.map(c=>c.turns.map(t=>({...t,start:'',end:'',verified:false})));let loopEnd=null;const player=document.getElementById('player');const files=document.getElementById('files');const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');DATA.forEach((c,i)=>files.add(new Option(c.name,i)));files.onchange=()=>{selected=+files.value;render()};
function render(){const c=DATA[selected];document.getElementById('title').textContent=c.name;document.getElementById('facts').textContent=`Реальных людей: ${c.expected} · ${c.duration.toFixed(2)} сек`;player.src='data:audio/wav;base64,'+c.audio;loopEnd=null;renderTurns();document.getElementById('model').innerHTML='<p>Модель нашла: '+c.summary.found_speakers+'</p><table><tr><th>Окно</th><th>Контекст</th><th>Участок</th><th>Метка</th></tr>'+c.prediction.map((w,i)=>`<tr><td><button onclick="playWindow(${i})">▶ ${i}</button></td><td>${w.context_start.toFixed(3)}–${w.context_end.toFixed(3)}</td><td>${w.start.toFixed(3)}–${w.end.toFixed(3)}</td><td>${esc(w.speaker)}</td></tr>`).join('')+'</table>'}
function renderTurns(){document.getElementById('turns').innerHTML=drafts[selected].map((t,i)=>`<div class="turn"><div class="row"><b>Реплика ${i+1}</b><select onchange="edit(${i},'speaker',this.value)">${Array.from({length:DATA[selected].expected},(_,j)=>`<option value="speaker_${j+1}" ${t.speaker==='speaker_'+(j+1)?'selected':''}>Говорящий ${j+1}</option>`).join('')}<option value="speaker_unknown" ${t.speaker==='speaker_unknown'?'selected':''}>Не определён</option></select><input type="number" step="0.001" min="0" placeholder="начало" value="${t.start}" onchange="edit(${i},'start',this.value)"><button onclick="stamp(${i},'start')">Начало сейчас</button><input type="number" step="0.001" min="0" placeholder="конец" value="${t.end}" onchange="edit(${i},'end',this.value)"><button onclick="stamp(${i},'end')">Конец сейчас</button><button onclick="playTurn(${i})">▶</button><label><input type="checkbox" ${t.verified?'checked':''} onchange="edit(${i},'verified',this.checked)">Проверено</label></div><div class="words" contenteditable onblur="edit(${i},'text',this.innerText)">${esc(t.text)}</div></div>`).join('');updateStatus()}
function edit(i,k,v){drafts[selected][i][k]=v;if(k!=='verified'){drafts[selected][i].verified=false;renderTurns()}else updateStatus()}
function stamp(i,k){edit(i,k,player.currentTime.toFixed(3));renderTurns()}
function addTurn(){drafts[selected].push({speaker:'speaker_1',text:'',start:'',end:'',verified:false});renderTurns()}
function jump(n){loopEnd=null;player.currentTime=Math.max(0,Math.min(DATA[selected].duration,player.currentTime+n))}
function playTurn(i){const t=drafts[selected][i];if(t.start==='')return;player.currentTime=+t.start;loopEnd=t.end===''?null:+t.end;player.play()}
function playWindow(i){const w=DATA[selected].prediction[i];player.currentTime=w.context_start;loopEnd=w.context_end;player.play()}
player.ontimeupdate=()=>{document.getElementById('clock').textContent=player.currentTime.toFixed(3)+' сек';if(loopEnd!==null&&player.currentTime>=loopEnd){player.pause();loopEnd=null}};
function updateStatus(){const rows=drafts[selected];const good=rows.filter(t=>t.verified&&t.start!==''&&t.end!==''&&+t.start>=0&&+t.end>+t.start&&+t.end<=DATA[selected].duration+.02).length;document.getElementById('status').textContent=`Проверено корректных участков: ${good} / ${rows.length}`}
function download(name,content,type){const a=document.createElement('a');const url=URL.createObjectURL(new Blob([content],{type}));a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000)}
function saveDraft(){download('bimvo_reference_draft.json',JSON.stringify({schema:'bimvo-speaker-reference-v1',files:DATA.map((c,i)=>({file:c.name+'.m4a',source_sha256:c.sha,expected_speakers:c.expected,turns:drafts[i]}))},null,2),'application/json')}
function exportCsv(){let rows=['file,start,end,speaker,verified,source_sha256'];for(let i=0;i<DATA.length;i++){for(const t of drafts[i]){if(!t.verified||t.start===''||t.end===''||+t.start<0||+t.end<=+t.start||+t.end>DATA[i].duration+.02){alert('Сначала проверьте все участки всех файлов. Черновик можно сохранить отдельно.');return}if(t.speaker==='speaker_unknown'){alert('Неопределённый голос не может быть эталоном DER.');return}rows.push([DATA[i].name+'.m4a',+t.start,+t.end,t.speaker,'true',DATA[i].sha].join(','))}}download('bimvo_verified_reference.csv','\ufeff'+rows.join('\n'),'text/csv;charset=utf-8')}
document.getElementById('load').onchange=async e=>{try{const doc=JSON.parse(await e.target.files[0].text());for(let i=0;i<DATA.length;i++){const c=doc.files.find(x=>x.file===DATA[i].name+'.m4a');if(!c||c.source_sha256!==DATA[i].sha||!Array.isArray(c.turns))throw Error('Несовпадение аудио/схемы');drafts[i]=c.turns}render()}catch(error){alert('Не удалось загрузить черновик: '+error.message)}};render();</script>'''
    (out/'REVIEW.html').write_text(page.replace('__DATA__',payload),encoding='utf-8')

if __name__=='__main__':main()
