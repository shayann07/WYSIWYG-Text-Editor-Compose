"""Independent byte decoder and expected-state audit of the owned native workflow.

Does not drive UI or use the Kotlin serializer to construct expectations.
"""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse, hashlib, json, struct, sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from verification import validate_build

def decode(data):
    body=data[:-32]
    assert hashlib.sha256(body).digest()==data[-32:]
    magic,version,count=struct.unpack_from('>III',body)
    assert magic==0x41535450 and version==8 and count<=100000
    pos=12
    def num(fmt):
        nonlocal pos
        v=struct.unpack_from(fmt,body,pos);pos+=struct.calcsize(fmt)
        return v[0] if len(v)==1 else v
    def text():
        nonlocal pos
        n=num('>i');assert 0<=n<=100000
        v=body[pos:pos+n*2].decode('utf-16be','surrogatepass');pos+=n*2
        return v
    start=num('>BBBiBHHH')
    units=[num('>HBBBBiBHHH') for _ in range(count)]
    paragraphs=[]
    for _ in range(num('>i')):
        at,h,k,restart=num('>iBBH');item,parent=text(),text();font,size=num('>BB')
        paragraphs.append(dict(index=at,heading=h,kind=k,restart=restart,item=item,parent=parent,marker=[font,size]))
    links=[(text(),text()) for _ in range(num('>i'))]
    root_link=num('>i');linked=[num('>ii') for _ in range(num('>i'))]
    images=[]
    for _ in range(num('>i')):images.append(dict(unit=num('>i'),resource=text(),width=num('>H'),alt=text()))
    resources=[]
    for _ in range(num('>i')):
        identity=text();w,h=num('>ii');mime=text();n=num('>i');raw=body[pos:pos+n];pos+=n
        assert len(raw)==n and hashlib.sha256(raw).hexdigest()==identity
        resources.append(dict(id=identity,width=w,height=h,mime=mime,bytes=n))
    pages=num('>BBB');assert pos==len(body)
    return dict(start=start,units=units,text=b''.join(struct.pack('>H',u[0]) for u in units).decode('utf-16be','surrogatepass'),paragraphs=paragraphs,links=links,rootLink=root_link,linked=linked,images=images,resources=resources,pages=pages)

def semantic(d):
    # Cloning deliberately gives list and link identities fresh values. Preserve
    # equivalence classes, parent relationships and URI associations independently.
    d=dict(d);ids={p['item']:i for i,p in enumerate(d['paragraphs']) if p['item']}
    d['paragraphs']=[dict(p,item=ids.get(p['item']),parent=ids.get(p['parent'])) for p in d['paragraphs']]
    d['links']=[uri for _,uri in d['links']]
    return d

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--build-dir',type=Path,required=True);a=p.parse_args()
    root=a.root.resolve();build=a.build_dir.resolve();validate_build(build)
    rows=[json.loads(x) for x in (root/'protocol.jsonl').read_text().splitlines()]
    def win(label,i=1):return next(r for r in rows if r['label']==label)['response']['windows'][i]
    def raw(w,key='payload'):return (root/w[key+'File']).read_bytes()
    def doc(label,i=1):return decode(raw(win(label,i)))
    created=doc('created-article');numeric=doc('image-width-320');drag=doc('image-drag-360');replacement=doc('image-replaced');saved=doc('save-copy-complete')
    expected_line='یہ میری تحریر ہے — English café 123.'
    assert numeric['text']==created['text']+'\n'+expected_line+'\n\ufffc\n'
    at=len(created['units'])+1
    assert all(u[2]&1 for u in numeric['units'][at:at+len(expected_line)])
    english=next(p for p in numeric['paragraphs'] if p['parent'])
    assert english['kind']==1 and english['parent'] in {p['item'] for p in numeric['paragraphs']}
    assert numeric['images'][0]['width']==320 and drag['images'][0]['width']==360
    assert raw(win('drag-undo'))==raw(win('image-width-320'))
    assert raw(win('drag-redo'))==raw(win('image-drag-360'))==raw(win('properties-cancel'))
    assert win('image-drag-360')['undo']==win('image-width-320')['undo']+1
    assert win('properties-cancel')['undo']==win('drag-redo')['undo']
    assert replacement['text']==drag['text'] and replacement['units']==drag['units']
    assert replacement['images'][0]['width']==360 and replacement['images'][0]['alt']=='خاکہ — illustration'
    assert replacement['images'][0]['resource']!=drag['images'][0]['resource']
    assert {r['id'] for r in drag['resources']} <= {r['id'] for r in replacement['resources']}
    assert saved['pages']==(1,1,20) and saved['images']==replacement['images']
    assert (root/'local-copy.astra').read_bytes()==raw(win('save-copy-complete'))
    assert not win('save-copy-complete')['dirty']
    external=(root/'external-expected.astra').read_bytes()
    named=Path(win('first-save-complete')['path'])
    assert named.is_relative_to(root) and named.read_bytes()==external
    assert decode(external)['text']=='EXTERNAL VERSION — '+saved['text']
    fault=win('durable-candidate-quarantine')
    assert raw(fault)==raw(win('save-copy-complete'))
    assert fault['unfinished']['published']==fault['unfinished']['generation']==2
    assert not fault['unfinished']['running'] and not fault['unfinished']['pending']
    assert fault['committedRecovery']['completed']==fault['committedRecovery']['observed']
    assert fault['marked']==[264,282]
    expected_candidate=saved['text']+'نامکمل کِ 👩🏽‍💻\ud800'
    restored=win('restored-unfinished');rd=decode(raw(restored))
    assert rd['text']==expected_candidate and restored['path'] is None and restored['dirty']
    assert restored['marked'] is None and restored['undo']==restored['redo']==0
    expected=dict(saved);expected['text']=expected_candidate
    # All pre-existing unit formatting, structure, pages and embedded resources survive.
    assert rd['units'][:264]==saved['units'] and rd['resources']==saved['resources'] and rd['images']==saved['images'] and rd['pages']==saved['pages']
    assert semantic(rd)['paragraphs']==semantic(saved)['paragraphs']
    assert raw(win('restored-saved'))==(root/'restored-candidate.astra').read_bytes()==raw(win('fresh-open-candidate'))
    reopened=win('fresh-open-candidate');assert not reopened['dirty'] and reopened['undo']==reopened['redo']==0 and reopened['marked'] is None
    assert semantic(doc('workspace-match-restored',2))==semantic(saved)
    second=win('second-restored-committed',3)
    assert decode(raw(second))['text']=='دوسری دستاویز — Second document 456'
    assert second['zoom']==.75 and not second['paged'] and second['selection']==[35,35] and second['undo']==0
    instance=win('template-created-final',4)
    assert semantic(decode(raw(instance)))==semantic(decode(raw(second))) and instance['path'] is None and instance['dirty'] and instance['undo']==0
    export=(root/'quarantine-export.astra-recovery').read_bytes()
    assert hashlib.sha256(export[:-32]).digest()==export[-32:]
    assert struct.unpack_from('>II',export)==(0x41555245,1)
    pos=8;parts=[]
    for _ in range(3):
        length=struct.unpack_from('>i',export,pos)[0];pos+=4
        assert 0<=length<=64*1024*1024
        parts.append(export[pos:pos+length]);pos+=length
    assert pos==len(export)-32
    record,base,delta=parts
    assert hashlib.sha256(record[:-32]).digest()==record[-32:]
    assert 'unverified \ud800 callback'.encode('utf-16be','surrogatepass') in record
    assert len(base)>900000 and delta==b''
    base_hash=hashlib.sha256(base).hexdigest()
    assert base_hash.encode('utf-16be') in record
    assert any(p.read_bytes()==base for p in (root/'recovery'/'unfinished-records').rglob(base_hash+'*') if p.is_file())
    killed=[json.loads(x) for x in (root/'processes.jsonl').read_text().splitlines()]
    assert any(x['pid']==4788 and x['exitCode']!=0 for x in killed)
    assert not (root/'stderr.log').exists() or not (root/'stderr.log').read_text().strip()
    result=dict(status='PASS',scope='Independent exact native payload, formatting, structure, resources, history, Save conflict and post-kill recovery audit; remaining UI checks are separate',buildManifestSha256=hashlib.sha256((build/'build-identity.json').read_bytes()).hexdigest(),ownedKilledPid=4788,committedUnits=264,recoveredDraftUnits=282,secondUnits=35,finalFileSha256=hashlib.sha256((root/'restored-candidate.astra').read_bytes()).hexdigest(),remainingNative='BLOCKED modal control routing: discard/new arrivals, Locate/remove/clear, template removal; image transform/window matrix native supplement NOT RUN')
    (root/'audit.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()
