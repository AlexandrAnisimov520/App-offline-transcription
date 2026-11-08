#!/usr/bin/env python3
"""Run actual core logic, parse all Java, and verify JSON + SQLite contracts without Android SDK."""
from pathlib import Path
import json, re, shutil, sqlite3, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/bimvo/app'

def run(*command):
    subprocess.run(list(map(str,command)),cwd=ROOT,check=True)

with tempfile.TemporaryDirectory(prefix='bimvo-qa-') as temp:
    out=Path(temp); classes=out/'classes'; classes.mkdir()
    run('javac','-encoding','UTF-8','--release','17','-d',classes,
        *(SRC/f'{name}.java' for name in ['Utterance','Recording','TranscriptText','ImportFiles']),
        ROOT/'qa/CoreTests.java',ROOT/'qa/JavaSyntaxCheck.java')
    run('java','-cp',classes,'CoreTests',out)
    run('java','-cp',classes,'JavaSyntaxCheck',*sorted(SRC.glob('*.java')))
    exported=json.loads((out/'round_trip.json').read_text(encoding='utf-8'))
    assert set(exported)=={'transcript'}
    for turn in exported['transcript']:
        assert set(turn)=={'speaker_id','start_ms','end_ms','text'}
        assert type(turn['start_ms']) is int and type(turn['end_ms']) is int
        assert 0<=turn['start_ms']<turn['end_ms']
        assert isinstance(turn['speaker_id'],str) and isinstance(turn['text'],str)
    assert exported['transcript'][0]['text']=='Hello "world"!\nLine\\two\t\x01 \u041f\u0440\u0438\u0432\u0435\u0442 \U0001f3a4'
    assert exported['transcript'][0]['start_ms']==1250
    print('PASS: exact JSON schema, integer milliseconds, UTF-8 and emoji round trip')

# Execute production CREATE statements in SQLite, not a retyped approximation.
source=(SRC/'LibraryStore.java').read_text(encoding='utf-8')
blocks=re.findall(r'db\.execSQL\((.*?)\);',source,re.S)
schema=[''.join(json.loads(literal) for literal in re.findall(r'"(?:[^"\\]|\\.)*"',block)) for block in blocks]
assert len(schema)==3
connection=sqlite3.connect(':memory:')
connection.execute('PRAGMA foreign_keys=ON')
for statement in schema: connection.execute(statement)
insert_record='INSERT INTO recordings VALUES (?,?,?,?,?,?,?,?,?,?)'
connection.execute(insert_record,('a','Audio','a.mp3','a.audio','audio/mpeg','hash-a',100,60000,2,0))
connection.execute(insert_record,('b','Audio B','b.mp3','b.audio','audio/mpeg','hash-b',200,-1,1,0))
connection.execute('INSERT INTO utterances VALUES (?,?,?,?,?,?)',('a',0,'speaker_1',1250,4380,'Hello'))
connection.execute('INSERT INTO utterances VALUES (?,?,?,?,?,?)',('b',0,'speaker_2',0,2000,'Different recording'))
count=0
for statement,args,label in [
    ('INSERT INTO utterances VALUES (?,?,?,?,?,?)',('a',1,'speaker_1',1000,1000,'zero'),'zero interval'),
    ('INSERT INTO utterances VALUES (?,?,?,?,?,?)',('a',1,'speaker_1',-1,2000,'negative'),'negative start'),
    ('INSERT INTO utterances VALUES (?,?,?,?,?,?)',('missing',0,'speaker_1',0,2000,'orphan'),'orphan turn'),
    (insert_record,('c','dup','c.mp3','c.audio','audio/mpeg','hash-a',100,1,3,0),'duplicate SHA-256')]:
    try: connection.execute(statement,args)
    except sqlite3.IntegrityError: print('PASS: SQLite rejects '+label);count+=1
    else: raise AssertionError('SQLite accepted '+label)
assert connection.execute('SELECT id FROM recordings ORDER BY created_at DESC,id').fetchall()==[('a',),('b',)]
print('PASS: SQLite newest-first ordering')
connection.execute('UPDATE utterances SET speaker_id=?,start_ms=?,end_ms=? WHERE recording_id=? AND turn_id=?',('speaker_3',2000,5000,'a',0))
assert connection.execute('SELECT speaker_id,start_ms,end_ms FROM utterances WHERE recording_id=?',('a',)).fetchone()==('speaker_3',2000,5000)
assert connection.execute('SELECT speaker_id FROM utterances WHERE recording_id=?',('b',)).fetchone()==('speaker_2',)
print('PASS: SQLite time/speaker edit is isolated to the chosen recording')
connection.execute('DELETE FROM recordings WHERE id=?',('a',))
assert connection.execute('SELECT recording_id FROM utterances').fetchall()==[('b',)]
print('PASS: SQLite cascade deletes only the chosen record turns')
connection.close()
print('RESULT: JSON contract and 7 SQLite checks passed (host SQLite, not Android instrumentation)')
