"""Resolve historical root-relative document names after the docs/ organization."""
from pathlib import Path
import json,os,re
from urllib.parse import unquote

ROOT=Path(__file__).resolve().parents[1]
MAP=ROOT/'docs/relocations.json'
def relocations():return json.loads(MAP.read_text(encoding='utf-8'))['paths'] if MAP.exists() else {}
def resolve(name):
    name=str(name).replace('\\','/')
    return ROOT/relocations().get(name,name)
def canonical(name):return resolve(name).relative_to(ROOT).as_posix()

def relocate_links(text,original,output):
    """Rewrite existing local Markdown link destinations, leaving prose/code/URLs alone."""
    oldbase=(ROOT/original).parent
    newbase=(ROOT/output).parent
    mapping=relocations()
    pattern=re.compile(r'(!?\[[^\]\n]*\]\()(<[^>\n]+>|[^)\n]+)(\))')
    def change(match):
        raw=match[2];value=raw.strip('<>')
        root_prefix=ROOT.as_posix()+'/'
        local_absolute=value.replace('\\','/').lower().startswith(root_prefix.lower())
        if (re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:',value) and not local_absolute) or value.startswith(('#','/')):return match[0]
        target,sep,fragment=value.partition('#')
        if not target:return match[0]
        candidate=Path(unquote(target)).resolve() if local_absolute else (oldbase/unquote(target)).resolve()
        if not candidate.is_relative_to(ROOT):return match[0]
        rel=candidate.relative_to(ROOT).as_posix();dest=ROOT/mapping.get(rel,rel)
        if not dest.exists():return match[0] # historical missing evidence remains explicitly historical
        fixed=os.path.relpath(dest,newbase).replace('\\','/')
        if sep:fixed+='#'+fragment
        if ' ' in fixed:fixed='<'+fixed+'>'
        return match[1]+fixed+match[3]
    return pattern.sub(change,text)
