#!/usr/bin/env python3
"""Portable structural checks. This is not an Android build or an emulator run."""
from pathlib import Path
from xml.etree import ElementTree as ET
import hashlib, json, re, sys, zipfile, struct
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app/src/main'
SRC=APP/'java/com/bimvo/app'
checks=[]
def check(condition,label):
    checks.append((bool(condition),label))
    print(('PASS: ' if condition else 'FAIL: ')+label)

for rel in ['settings.gradle','build.gradle','gradlew','gradlew.bat','gradle.properties',
            'gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties',
            'app/build.gradle','app/src/main/AndroidManifest.xml','README.md']:
    check((ROOT/rel).is_file(),rel)
check(zipfile.is_zipfile(ROOT/'gradle/wrapper/gradle-wrapper.jar'),'Gradle bootstrap jar readable')
classes=['MainActivity','BimvoApplication','AudioImporter','ImportFiles','LibraryStore',
         'Recording','Utterance','TranscriptText','TimelineView','BimvoStyle','PrismView','GlyphView','DotGridView']
for name in classes:check((SRC/f'{name}.java').is_file(),'production source: '+name)
for path in APP.rglob('*.xml'):
    try:ET.parse(path); valid=True
    except ET.ParseError:valid=False
    check(valid,'XML parses: '+str(path.relative_to(APP)))
manifest=ET.parse(APP/'AndroidManifest.xml').getroot()
ns='{http://schemas.android.com/apk/res/android}'
check(not manifest.findall('uses-permission'),'no Internet, microphone or broad storage permissions')
application=manifest.find('application')
check(application.get(ns+'name')=='.BimvoApplication','application-scoped IO state registered')
check(application.get(ns+'allowBackup')=='false','automatic application backup disabled')
check(application.get(ns+'icon')=='@mipmap/ic_launcher','new launcher icon registered')
check(application.get(ns+'roundIcon')=='@mipmap/ic_launcher','adaptive round icon registered')
resources={}
for path in (APP/'res/values').glob('*.xml'):
    for element in ET.parse(path).getroot():
        resources.setdefault(element.tag,set()).add(element.attrib.get('name'))
all_java='\n'.join(path.read_text(encoding='utf-8') for path in SRC.glob('*.java'))
string_refs=set(re.findall(r'(?<!android\.)R\.string\.(\w+)',all_java))
check(not string_refs-resources['string'],'all Java string references resolve')
for name in set(re.findall(r'(?<!android\.)R\.drawable\.(\w+)',all_java)):
    check(any(file.stem==name for file in (APP/'res').glob('drawable*/*')),'drawable reference resolves: '+name)
main=(SRC/'MainActivity.java').read_text(encoding='utf-8')
store=(SRC/'LibraryStore.java').read_text(encoding='utf-8')
app=(SRC/'BimvoApplication.java').read_text(encoding='utf-8')
importer=(SRC/'AudioImporter.java').read_text(encoding='utf-8')
copy=(SRC/'ImportFiles.java').read_text(encoding='utf-8')
timeline=(SRC/'TimelineView.java').read_text(encoding='utf-8')
exporter=(SRC/'TranscriptText.java').read_text(encoding='utf-8')
for page in ['SPLASH','LOGIN','HOME','UPLOAD','PROCESSING','TRANSCRIPT','MAP','RECORDS','EXPORT','PROFILE','DETAILS']:
    check(f'case {page}:' in main,'screen routed: '+page)
for key in ['onboarding_lecture','onboarding_meeting','onboarding_personal']:
    check('R.string.'+key in main,'optional use case: '+key)
check('getBoolean("onboardingDone", false)' in main,'introduction gated by saved completion')
check('putBoolean("onboardingDone",true)' in main,'skip/finish saved persistently')
check('onboardingChoice' in main and 'out.putInt("onboardingChoice"' in main,'onboarding stage saved for rotation')
check('onboarding_skip' in main and 'finishOnboarding' in main,'skip action wired')
check('Intent.ACTION_OPEN_DOCUMENT' in main,'real system audio picker')
check('Intent.EXTRA_LOCAL_ONLY, true' in main,'local file picker requested')
check('Intent.ACTION_CREATE_DOCUMENT' in main,'real output document picker')
check('resolver.openInputStream(uri)' in importer,'selected content URI actually read')
check('getFilesDir(), "audio"' in store,'private persistent audio folder (not cache-only)')
check('64 * 1024' in copy,'bounded 64 KiB copying buffer')
check('FREE_SPACE_RESERVE' in copy,'disk reserve checked during copying')
check('InterruptedIOException' in copy and 'cancelImport::get' in app,'cancellable import path')
check('getFD().sync()' in copy,'audio bytes flushed before commit')
check('Collections.emptyList()' in importer,'imported recording has no fabricated transcript')
check('MessageDigest.getInstance("SHA-256")' in copy and 'findHash(copy.sha256)' in importer,'content duplicate detection')
check('sha256 TEXT UNIQUE' in store,'database also guards duplicate hashes')
check('file.delete()' in store and 'name.endsWith(".part")' in store,'interrupted copies cleaned at startup')
check('releasePersistableUriPermission' in app,'temporary URI permissions released')
check('Executors.newSingleThreadExecutor()' in app,'file and database work off UI thread')
check('removeObserver(this)' in main,'screen observer detached when stopped')
check('handler.removeCallbacksAndMessages(null)' in main,'Activity callbacks cleared on destroy')
check('putBoolean("hints",checked).apply()' in main,'hint switch persists across launches')
check('putInt("sort",which).apply()' in main,'sorting persists across launches')
check('r.matches(recordQuery)' in main,'name/transcript search wired')
check('renameRecording' in main and 'model.delete(r)' in main,'rename and confirmed deletion wired')
check('current().transcript,selectedUtterance().id,current().transcriptDurationMs()' in main,'timeline gets actual selected-record turns')
check('FRAGMENTS' not in timeline,'no independent hard-coded timeline intervals')
check('for (Utterance turn : r.transcript)' in main,'transcript gets the same record turns')
check('TranscriptText.format(recording.transcript, format)' in app,'export gets the same record turns')
check('model.updateUtterance' in main and 'start_ms' in store and 'end_ms' in store,'speaker and time edits persisted')
check('!r.transcript.isEmpty() && !model.busy()' in main,'unprocessed export disabled')
check('DEMO_' in app and 'export_demo_notice' in main,'demo export explicitly identified')
check('current() == null' in main,'restored deleted-record state guarded')
check('loginPassword' not in main[main.index('onSaveInstanceState'):main.index('private void restore')],'demo password not persisted')
check('DROP TABLE' not in store,'no destructive database upgrade fallback')
check('EXTERNAL_STORAGE' not in all_java and 'READ_MEDIA_AUDIO' not in all_java,'no broad file permission requests')

for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    path=APP/'res'/f'mipmap-{density}/ic_launcher.png'
    blob=path.read_bytes() if path.exists() else b''
    check(blob[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',blob[16:24])==(size,size),'launcher PNG size: '+density)
foreground=APP/'res/drawable-nodpi/ic_launcher_foreground.png'
blob=foreground.read_bytes()
check(struct.unpack('>II',blob[16:24])==(432,432),'adaptive artwork is 432 x 432')
for ref in ['splash_approved.png','login_approved.png','launcher_icon_approved.png','home_approved_structure.png',
            'records_approved_structure.png','export_approved_structure.png','profile_approved_structure.png']:
    check((ROOT/'design/references'/ref).is_file(),'approved reference retained: '+ref)
expected=(ROOT/'qa/icon_source.sha256').read_text().split()[0]
actual=hashlib.sha256((ROOT/'design/references/launcher_icon_approved.png').read_bytes()).hexdigest()
check(actual==expected,'launcher source matches user-provided artwork byte-for-byte')
appgradle=(ROOT/'app/build.gradle').read_text()
check('versionCode 3' in appgradle and "versionName '0.3-local'" in appgradle,'v3 version metadata')
check('compileSdk 35' in appgradle and 'minSdk 26' in appgradle and 'targetSdk 34' in appgradle,'SDK targets unchanged')
check("applicationId 'com.bimvo.app'" in appgradle,'application ID unchanged')
check("version '8.7.3'" in (ROOT/'build.gradle').read_text(),'AGP version unchanged')
check('gradle-8.10.2-bin.zip' in (ROOT/'gradle/wrapper/gradle-wrapper.properties').read_text(),'Gradle version unchanged')
check(not list(ROOT.rglob('*.apk')),'no unbuilt/unverified APK included')
check(not list(ROOT.rglob('local.properties')),'no machine-specific SDK paths included')
check('EXIT /B %EXIT_CODE%' in (ROOT/'gradlew.bat').read_text(),'Windows launcher propagates build failure status')
failed=[label for ok,label in checks if not ok]
print(f'RESULT: {len(checks)-len(failed)} PASS / {len(failed)} FAIL')
sys.exit(bool(failed))
