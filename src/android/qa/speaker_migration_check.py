"""Production schema 1/2/3->4 plus overlap, atomic publication and cascade regression."""
import json,re,sqlite3
from pathlib import Path
source=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/bimvo/app/LibraryStore.java').read_text(encoding='utf-8-sig')
def sqls(body):
 return [''.join(json.loads(x) for x in re.findall(r'"(?:[^"\\]|\\.)*"',e)) for e in re.findall(r'db.execSQL\((.*?)\);',body,re.S)]
processing=sqls(source.split('private void createProcessingTables',1)[1].split('static final class Job',1)[0])
speakers=sqls(source.split('private void createSpeakerTables',1)[1].split('private void createProcessingTables',1)[0])
assert len(processing)==len(speakers)==2
alter=re.search(r'db.execSQL\("(ALTER TABLE audio_chunks[^"\n]+)"\)',source).group(1)
for version in (1,2,3):
 db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
 db.execute('CREATE TABLE recordings (id TEXT PRIMARY KEY)');db.execute("INSERT INTO recordings VALUES ('a')")
 db.execute('CREATE TABLE utterances (recording_id TEXT, text TEXT)');db.execute("INSERT INTO utterances VALUES ('a','old text')")
 if version>=2:
  db.execute(processing[0]);db.execute(processing[1] if version==3 else processing[1].replace(", speaker_id TEXT NOT NULL DEFAULT 'speaker_unknown'",''))
  db.execute("INSERT INTO audio_chunks(recording_id,chunk_index,start_sample,end_sample,cut_reason) VALUES('a',0,0,16000,'end')")
 if version==1:
  for sql in processing:db.execute(sql)
 elif version==2:db.execute(alter)
 for sql in speakers:db.execute(sql)
 assert db.execute('SELECT text FROM utterances').fetchone()[0]=='old text'
 db.execute("INSERT INTO speaker_intervals VALUES('a',0,0,16000,'speaker_1')")
 db.executemany("INSERT INTO pending_speaker_intervals VALUES('a',?,?,?,?)",[(0,0,16000,'speaker_2'),(1,4000,6000,'speaker_3')]);db.commit()
 # Same publication SQL as completeAudioJob. A failure must retain the previous visible result.
 publish=re.search(r'db.execSQL\("(INSERT INTO speaker_intervals SELECT[^"\n]+)"',source).group(1)
 db.execute('BEGIN');db.execute("DELETE FROM speaker_intervals WHERE recording_id='a'");db.execute(publish,('a',));db.rollback()
 assert db.execute('SELECT speaker_id FROM speaker_intervals').fetchall()==[('speaker_1',)]
 db.execute("DELETE FROM speaker_intervals WHERE recording_id='a'");db.execute(publish,('a',))
 assert db.execute('SELECT count(*) FROM speaker_intervals').fetchone()[0]==2
 db.execute("DELETE FROM recordings WHERE id='a'")
 for table in ['speaker_intervals','pending_speaker_intervals','audio_chunks']:assert db.execute('SELECT count(*) FROM '+table).fetchone()[0]==0
 print(f'PASS: migration {version}->4, old transcript, overlap, failed publication rollback, cascade')
 db.close()
