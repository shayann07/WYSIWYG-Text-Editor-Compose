"""Independent parser/render checks of the unchanged rejected diagnostic, not export qualification."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
from pathlib import Path
import argparse,hashlib,json,subprocess,sys
import pypdf,pymupdf
p=argparse.ArgumentParser();p.add_argument('--candidate',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args();out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
poppler=Path('C:/Users/shaya/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/poppler/Library/bin/pdftoppm.exe')
info=poppler.with_name('pdfinfo.exe');version=subprocess.run([str(poppler),'-v'],capture_output=True,text=True);(out/'poppler-version.txt').write_text(version.stdout+version.stderr)
render=subprocess.run([str(poppler),'-png','-r','72',str(a.candidate.resolve()),str(out/'page')],capture_output=True,text=True,timeout=45);assert render.returncode==0,render.stderr
plan=json.loads(a.plan.read_text(encoding='utf-8'));reader=pypdf.PdfReader(a.candidate);mupdf=pymupdf.open(a.candidate);rows=[]
for page,(one,two) in enumerate(zip(reader.pages,mupdf)):
 expected=''.join(b''.join(int(u).to_bytes(2,'little') for u in r['utf16']).decode('utf-16le','strict') for r in plan['semanticRuns'] if r['page']==page)
 first=one.extract_text();second=two.get_text()
 # Generated layout CR/LF only; preserve every letter/mark/space/bidi control.
 strip=lambda s:s.replace('\n','').replace('\r','')
 rows.append({'page':page+1,'expected':expected,'pypdf':first,'mupdf':second,'pypdfExactExceptGeneratedCRLF':strip(first)==strip(expected),'mupdfExactExceptGeneratedCRLF':strip(second)==strip(expected),'paperPoints':[float(one.mediabox.width),float(one.mediabox.height)]})
assert len(rows)==plan['pageCount'] and len(list(out.glob('page-*.png')))==len(rows)
result={'status':'REJECTED_CANDIDATE_INDEPENDENT_CHECKS_EXECUTED','pdfSha256':hashlib.sha256(a.candidate.read_bytes()).hexdigest(),'pypdfVersion':pypdf.__version__,'mupdfVersion':pymupdf.VersionBind,'popplerVersion':(version.stdout+version.stderr).strip(),'pages':rows,'scope':'Three independent codebases; raw text comparisons preserve marks/order/spacing. Not a qualified output; original rejection remains.'}
(out/'results.json').write_text(json.dumps(result,ensure_ascii=True,indent=2)+'\n');print(json.dumps({'status':result['status'],'exactText':[(r['pypdfExactExceptGeneratedCRLF'],r['mupdfExactExceptGeneratedCRLF']) for r in rows]}))
