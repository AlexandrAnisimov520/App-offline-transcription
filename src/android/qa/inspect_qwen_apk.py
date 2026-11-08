"""Inspect the packaged native payload; this does not execute inference on Android."""
import sys, zipfile, struct
from pathlib import Path
apk=Path(sys.argv[1])
with zipfile.ZipFile(apk) as z:
    libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert set(libs)=={'lib/arm64-v8a/libbimvo_llama.so','lib/arm64-v8a/libc++_shared.so'},libs
    for name in libs:
        data=z.read(name)
        assert data[:5]==b'\x7fELF\x02' and struct.unpack_from('<H',data,18)[0]==183,name
        phoff=struct.unpack_from('<Q',data,32)[0]
        phsize,phnum=struct.unpack_from('<HH',data,54)
        loads=[]
        for i in range(phnum):
            at=phoff+i*phsize
            if struct.unpack_from('<I',data,at)[0]==1:
                alignment=struct.unpack_from('<Q',data,at+48)[0]
                assert alignment>=16384,(name,alignment)
                loads.append(alignment)
        assert loads,name
        print('PASS: ARM64 ELF with 16 KiB-compatible LOAD alignment:',name)
        if name.endswith('libbimvo_llama.so'):
            # Names must be present in the binary; separate readelf verification can confirm export visibility.
            for method in ['nativeCreate','nativeCancel','nativeDestroy','nativeGenerate']:
                symbol='Java_com_bimvo_llm_LlamaSession_'+method
                assert symbol.encode() in data,symbol
            print('PASS: all four JNI entry-point names present')
    assert not any(n.endswith('.gguf') for n in z.namelist())
    print('PASS: no model weights bundled into APK')
print('RESULT: APK payload inspected; device execution remains untested')
