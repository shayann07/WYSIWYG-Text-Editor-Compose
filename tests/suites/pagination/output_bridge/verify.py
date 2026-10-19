"""Two bounded route decisions; no hidden text, custom writer or substitute shaping."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
from pathlib import Path
import argparse,hashlib,json,subprocess,zipfile,sys
ROOT = _REPO_ROOT;sys.path.insert(0,str(ROOT/'desktop-prototype'))
from verification import validate_build
p=argparse.ArgumentParser();p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--glyph-results',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args()
b=validate_build(a.build_dir);out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir();libs=Path(__file__).parent/'target/lib';jars=sorted(libs.glob('*.jar'));assert len(jars)==4
cp=str(b/'classes')+';'+str(b/'lib/*')+';'+str(libs/'*')
for name,cmd in [('compile',['javac','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(Path(__file__).with_name('Capability.java'))]),('capability',['java','-cp',str(classes)+';'+cp,'Capability',str(ROOT/'tests/suites/urdu/u0/fonts/NotoNastaliqUrdu[wght].ttf')])]:
 r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=45);(out/(name+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stdout+r.stderr
log=(out/'capability.log').read_text();assert 'SKIA_PDF_DOCUMENT=false' in log and 'FONT_EMBEDDED=true' in log
g=json.loads(a.glyph_results.read_text());assert g['matchingLayoutConfigurations']==[]
mixed=next(x for x in g['samples'] if x['name']=='mixed' and x['width']==180);trial=next(x for x in mixed['trials'] if x['factory']=='shaper-driven' and x['subpixel']);assert trial['lineRangeParity'] is False
notices={}
for jar in jars:
 with zipfile.ZipFile(jar) as z:
  for name in z.namelist():
   if name.startswith('META-INF/') and ('LICENSE' in name or 'NOTICE' in name):
    target=out/(jar.stem+'-'+Path(name).name);target.write_bytes(z.read(name));notices[target.name]=hashlib.sha256(target.read_bytes()).hexdigest()
result={'status':'OUTPUT_BRIDGE_BLOCKED','routes':[{'name':'pinned Skia paragraph direct PDF','version':'Skiko0.150.1','result':'BLOCKED','reason':'No installed org.jetbrains.skia.Document PDF binding, no Paragraph glyph visitor/TextBlob access'}, {'name':'PDFBox positioned glyph packaging plus existing RunHandler','version':'PDFBox3.0.5 isolated, not adopted','result':'REJECTED_LAYOUT_SUBSTITUTION','reason':'Font embedding callable; actual editor glyph/cluster boundary still missing; independent shaper fails unchanged mixed180 witness'}], 'mixedExpected':mixed['composeLines'],'mixedObserved':trial['lineRanges'],'widthErrors':trial['lineWidthErrors'],'tolerancePt':0.25,'pdfProduced':False,'dependencies':{j.name:hashlib.sha256(j.read_bytes()).hexdigest() for j in jars},'notices':notices,'buildManifestSha256':hashlib.sha256((b/'build-identity.json').read_bytes()).hexdigest(),'scope':'Not a general impossibility claim; no semantic PDF or print qualification. Continue independent typography/pages.'}
(out/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
