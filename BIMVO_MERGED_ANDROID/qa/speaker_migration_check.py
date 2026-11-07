"""Validate the SQL used by the production 1/2 -> 3 migrations on isolated in-memory databases."""
import json,re,sqlite3
from pathlib import Path
source=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/bimvo/app/LibraryStore.java').read_text(encoding='utf-8-sig')
body=source.split('    private void createProcessingTables',1)[1].split('    static final class Job',1)[0]
creates=[]
for expression in re.findall(r'db\.execSQL\((.*?)\);',body,re.S):
    creates.append(''.join(json.loads('"'+x+'"') for x in re.findall(r'"((?:\\.|[^"\\])*)"',expression)))
assert len(creates)==2
alter=re.search(r'db\.execSQL\("(ALTER TABLE audio_chunks[^"\n]+)"\)',source).group(1)
for version in (1,2):
    db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
    db.execute('CREATE TABLE recordings (id TEXT PRIMARY KEY)');db.execute("INSERT INTO recordings VALUES ('existing')")
    db.execute('CREATE TABLE utterances (recording_id TEXT, text TEXT)');db.execute("INSERT INTO utterances VALUES ('existing','Сохранённая расшифровка')")
    if version==2:
        db.execute(creates[0]);db.execute(creates[1].replace(", speaker_id TEXT NOT NULL DEFAULT 'speaker_unknown'",''))
        db.execute("INSERT INTO audio_chunks(recording_id,chunk_index,start_sample,end_sample,cut_reason) VALUES('existing',0,0,16000,'end')")
        db.execute(alter)
        assert db.execute('SELECT speaker_id FROM audio_chunks').fetchone()[0]=='speaker_unknown'
    else:
        for sql in creates:db.execute(sql)
    assert db.execute('SELECT text FROM utterances').fetchone()[0]=='Сохранённая расшифровка'
    assert 'speaker_id' in [r[1] for r in db.execute('PRAGMA table_info(audio_chunks)')]
    db.close();print(f'Database migration SQL {version}->3 retains records/transcript: OK')
