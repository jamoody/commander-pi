from pathlib import Path
import shutil,zipfile,json,subprocess,argparse,os
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--forge',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
f=a.forge.resolve(); module=f/'forge-gui-neo'
java_home=Path(os.environ['JAVA_HOME'])
dest=a.output.resolve(); dest.mkdir(parents=True,exist_ok=False)
app=dest/'app';app.mkdir(exist_ok=True)
for p in (module/'target/lib').glob('*.jar'): shutil.copy2(p,app/p.name)
shutil.copy2(module/'target/forge-gui-neo-2.0.16-SNAPSHOT.jar',app/'commander.jar')
res=f/'forge-gui/res'; skipped={'adventure','conquest','cube','draft','puzzle','sealed','tutorial'}
files=0; omitted=0; omitted_bytes=0
for p in res.rglob('*'):
    if not p.is_file(): continue
    rel=p.relative_to(res)
    if rel.parts[0] in skipped or (rel.parts[0]=='quest' and (len(rel.parts)<2 or rel.parts[1]!='commanderprecons')) or (rel.parts[0]=='deckgendecks' and p.name!='Commander.dat'):
        omitted+=1;omitted_bytes+=p.stat().st_size;continue
    if rel.parts[0] in ('cardsfolder','tokenscripts'): continue
    q=dest/'res'/rel;q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q);files+=1
for folder in ['cardsfolder','tokenscripts']:
    target=dest/'res'/folder; target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(target/'cardsfolder.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in (res/folder).rglob('*.txt'):
            z.write(p,p.relative_to(res/folder).as_posix());files+=1
if not (dest/'runtime').exists():
    subprocess.run([str(java_home/'bin/jlink.exe'),
        '--add-modules','java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.zipfs',
        '--strip-debug','--no-header-files','--no-man-pages','--output',str(dest/'runtime')],check=True)
shutil.copy2(root/'LICENSE',dest/'LICENSE-GPL3.txt')
shutil.copy2(root/'AVISOS.txt',dest/'AVISOS.txt')
(app/'launch.args').write_text('''-Xmx2g
-Dfile.encoding=UTF-8
-Dforge.assetsDir=./
-Dneo.dataDir=./datos
--add-opens=java.base/java.util=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
--add-opens=java.base/java.text=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED
--add-opens=java.base/java.math=ALL-UNNAMED
--add-opens=java.base/java.net=ALL-UNNAMED
-cp
app/*
''')
(dest/'Start Commander.cmd').write_text('@echo off\ncd /d "%~dp0"\nstart "Commander" "runtime\\bin\\javaw.exe" @app/launch.args forge.neo.NeoMain ui\n')
info={'resourceFilesRetained':files,'resourceFilesOmitted':omitted,'resourceBytesOmitted':omitted_bytes,'runtimeJars':len(list(app.glob('*.jar')))}
(dest/'package-stats.json').write_text(json.dumps(info,indent=2));print(info)
