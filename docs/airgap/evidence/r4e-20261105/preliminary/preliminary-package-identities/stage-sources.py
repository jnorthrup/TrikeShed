from pathlib import Path
import shutil,os,subprocess,json,hashlib
h=Path('/workspace/airgap-r4e-proof');old=Path('/workspace/airgap-r4d-proof/corresponding-source');out=h/'corresponding-source';repo=Path('/workspace/TrikeShed');head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
shutil.copytree(old,out,copy_function=os.link)
for p in out.glob('trikeshed-*.tar.gz'):p.unlink()
p=out/'README.md';s=p.read_text().replace('R4d','R4e').replace('8579dcad12d3b22c84ef3017157bf41e9a0fdbaf',head).replace('8579dcad1',head[:9]);p.unlink();p.write_text(s)
subprocess.run(['git','archive','--format=tar.gz','--prefix=TrikeShed/','--output='+str(out/('trikeshed-'+head[:9]+'.tar.gz')),head],cwd=repo,check=True)
p=out/'SHA256.json';p.unlink();p.write_text(json.dumps({p.relative_to(out).as_posix():hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in sorted(out.rglob('*')) if p.is_file() and p.name!='SHA256.json'},indent=2)+'\n')
subprocess.run(['python3',str(h/'sources.py'),'archive'],check=True)
p=h/'source-archive.json';r=json.loads(p.read_text());r['sourceCommit']=head;p.write_text(json.dumps(r,indent=2)+'\n')
