"""Stage an existing offline bundle; no model training or audio upload."""
from pathlib import Path
import argparse,zipfile,shutil
p=argparse.ArgumentParser();p.add_argument('--apk',type=Path);p.add_argument('--models-dir',type=Path);p.add_argument('--cam',type=Path);args=p.parse_args()
root=Path(__file__).resolve().parents[1];dest=root/'app/src/main/assets/models';dest.mkdir(parents=True,exist_ok=True)
if args.models_dir:shutil.copytree(args.models_dir,dest,dirs_exist_ok=True)
if args.apk:
 with zipfile.ZipFile(args.apk) as z:
  for item in z.infolist():
   if item.is_dir() or not item.filename.startswith('assets/models/'):continue
   rel=Path(item.filename[len('assets/models/'):]);target=dest/rel
   if '..' in rel.parts:raise ValueError('Unsafe archive path')
   target.parent.mkdir(parents=True,exist_ok=True)
   with z.open(item) as src,target.open('wb') as out:shutil.copyfileobj(src,out,65536)
if args.cam:
 target=dest/'diarization/campplus_zh_en.onnx';target.parent.mkdir(exist_ok=True);shutil.copyfile(args.cam,target)
print('Models staged. Gradle verifies pinned hashes before build.')
