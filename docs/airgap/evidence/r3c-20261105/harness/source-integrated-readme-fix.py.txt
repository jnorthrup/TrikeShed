from pathlib import Path
import json, hashlib, tarfile, gzip, shutil
h=Path('/workspace/airgap-r3c-proof'); root=h/'source-integrated-update'
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
meta=h/'source-integrated-update.json'; record=json.loads(meta.read_text())
shutil.copy2(meta,h/'source-integrated-update-initial.json')
shutil.copy2(root/'README.md',h/'source-integrated-readme-initial.md')
old=record['archive'];assert sha(Path(old['file']))==old['sha256']
(root/'README.md').write_text('''# R3c integrated source update

Begin with the R4e source companion and apply the R4f source update. Both archives are named and hashed in source-update.json. This yields source commit 4c00eccd7d2e8ce65ade1b23734c9aa1a018f0ab and the retained dependency sources. Apply this archive's trikeshed.patch with `git apply --binary`.

The full-index binary patch reproduces the tracked tree at combined D3M commit 97df0b59e5445e318f7f337ea0d4c395d17428ce. A temporary Git-index application verified that exact target tree. It includes the browser inspection changes, common/JVM NARS exact-role selection and null wired-identity refusal, and the documentation/evidence committed through that source identity. Both JVM and Spacegraph jars were rebuilt. Native modules, dependencies and their source inputs are unchanged.

This archive requires its two source bases and does not include later release-evidence commits. Complete vendor JDK patches and native-resource source rebuild provenance remain open. Consult the release verification packet for the exact tested source, build commands, runtime inventory and later publication identity.
''')
(root/'SHA256.json').write_text(json.dumps({p.name:sha(p) for p in sorted(root.iterdir()) if p.is_file() and p.name!='SHA256.json'},indent=2)+'\n')
dest=Path('/workspace/library-files/airgap-document-r3c-integrated-sources-update-v2-2026-11-05.tar.gz');assert not dest.exists()
with dest.open('wb') as raw,gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0,compresslevel=6) as gz,tarfile.open(fileobj=gz,mode='w|',format=tarfile.PAX_FORMAT) as tar:
 for p in sorted(root.iterdir()):
  info=tar.gettarinfo(str(p),arcname='airgap-document-source-update/'+p.name);info.uid=info.gid=0;info.uname=info.gname='';info.mtime=0;info.pax_headers={}
  with p.open('rb') as f:tar.addfile(info,f)
with tarfile.open(dest) as tar:
 for name,digest in json.loads((root/'SHA256.json').read_text()).items():assert hashlib.sha256(tar.extractfile('airgap-document-source-update/'+name).read()).hexdigest()==digest
record['archive']={'file':str(dest),'bytes':dest.stat().st_size,'sha256':sha(dest)}
record['packagingCorrection']={'previousArchiveRetained':old,'change':'README and its manifest describe the integrated JVM/common changes; source patch, base chain and verified target tree are identical.'}
meta.write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record['archive'],indent=2))
