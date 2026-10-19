"""Actual owned JVM kills plus independent big-endian v6-v9 semantic decoder.

The historical decode_v6 entry point is retained for qualification callers.
No Kotlin reader is used to establish the expected document description.
"""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
from pathlib import Path
import argparse,base64,hashlib,json,struct,subprocess,threading,queue,time,uuid

def decode_v6(data):
    body=data[:-32];assert hashlib.sha256(body).digest()==data[-32:]
    magic,v,n=struct.unpack('>III',body[:12]);assert magic==0x41535450 and v in (6,7,8,9) and 0<=n<=100000
    units=[struct.unpack('>HB',body[26+i*17:29+i*17]) for i in range(n)]
    text=b''.join(struct.pack('>H',u[0]) for u in units).decode('utf-16be','surrogatepass')
    pos=26+n*17
    def number(fmt='>i'):
        nonlocal pos
        size=struct.calcsize(fmt);assert pos+size<=len(body);r=struct.unpack(fmt,body[pos:pos+size]);pos+=size;return r[0] if len(r)==1 else r
    def string():
        nonlocal pos
        count=number();assert 0<=count<=6000000 and pos+count*2<=len(body);value=body[pos:pos+count*2].decode('utf-16be','surrogatepass');pos+=count*2;return value
    def style(at=None):
        values=number('>BBBi') if at is None else struct.unpack('>BBBi',body[at:at+7])
        bits,font,size,color=values;assert bits in range(16) and font in range(5) and (size==0 or 8<=size<=96) and -1<=color<=0xffffff
        return dict(bits=bits,font=font,size=size,color=color)
    def paragraph(at):
        bits,before,after,line=struct.unpack('>BHHH',body[at:at+7]);assert bits&3<3 and bits>>2<3 and before<=200 and after<=200 and (line==0 or 1000<=line<=3000)
        return dict(direction=bits&3,alignment=bits>>2,spaceBefore=before,spaceAfter=after,linePermille=line)
    styles=[style(29+i*17) for i in range(n)]
    all_paragraphs=[dict(index=0,start=0,properties=paragraph(19))]
    for index,(char,kind) in enumerate(units):
        assert kind in range(3)
        if kind==1:
            assert char==10 or (v>=9 and char==12)
            all_paragraphs[-1]['end']=index
            all_paragraphs.append(dict(index=len(all_paragraphs),start=index+1,properties=paragraph(36+index*17)))
    all_paragraphs[-1]['end']=n
    paragraphs=[]
    for _ in range(number()):
        index=number();heading,kind,restart=number('>BBH');paragraphs.append({'index':index,'heading':heading,'kind':kind,'restart':restart,'item':string(),'parent':string()})
        if v>=7:
            font,size=number('>BB');assert font in range(1,5) and 8<=size<=96;paragraphs[-1]['markerTypography']={'font':font,'size':size}
    links=[(string(),string()) for _ in range(number())];root_link=number();linked=[number('>ii') for _ in range(number())]
    images=[]
    for _ in range(number()):images.append({'unit':number(),'resource':string(),'width':number('>H'),'alt':string()})
    resources=[]
    for _ in range(number()):
        id=string();width=number();height=number();mime=string();size=number();raw=body[pos:pos+size];pos+=size
        assert hashlib.sha256(raw).hexdigest()==id and mime=='image/png' and raw[:8]==b'\x89PNG\r\n\x1a\n'
        assert struct.unpack('>II',raw[16:24])==(width,height)
        resources.append({'id':id,'width':width,'height':height,'mime':mime,'sha256':hashlib.sha256(raw).hexdigest()})
    page_settings=dict(paper=0,landscape=False,marginMm=14)
    if v>=8:
        paper,landscape,margin=number('>BBB');assert paper in (0,1) and landscape in (0,1) and 5<=margin<=40
        page_settings=dict(paper=paper,landscape=bool(landscape),marginMm=margin)
    rules=[dict(breakBefore=False,keepWithNext=False,keepLines=False,widows=2,orphans=2) for _ in all_paragraphs]
    page_breaks=[];running=dict(differentFirst=False,digits=0,header=None,footer=None)
    if v>=9:
        assert number()==len(all_paragraphs)
        rules=[]
        for _ in all_paragraphs:
            flags,widows,orphans=number('>BBB');assert flags in range(8) and 1<=widows<=5 and 1<=orphans<=5
            rules.append(dict(breakBefore=bool(flags&1),keepWithNext=bool(flags&2),keepLines=bool(flags&4),widows=widows,orphans=orphans))
        count=number();assert 0<=count<=n
        page_breaks=[number() for _ in range(count)]
        assert len(set(page_breaks))==count and all(0<=i<n and units[i]==(12,1) for i in page_breaks)
        assert set(page_breaks)=={i for i,u in enumerate(units) if u==(12,1)},'Form-feed metadata/body mismatch'
        first,digits=number('>BB');assert first in (0,1) and digits in range(3)
        running.update(differentFirst=bool(first),digits=digits)
        for name in ('header','footer'):
            present=number('>B');assert present in (0,1)
            if present:
                identity=string();raw=string();assert str(uuid.UUID(identity))==identity and len(raw.encode('utf-16be','surrogatepass'))//2<=512 and raw.count('\n')<=3
                typography=style();direction,alignment,field=number('>BBB');assert direction in range(3) and alignment in range(3) and field in range(3)
                assert typography['size']==0 or 8<=typography['size']<=32
                running[name]=dict(id=identity,text=raw,style=typography,direction=direction,alignment=alignment,field=field)
        assert not (running['header'] and running['footer']) or running['header']['id']!=running['footer']['id']
    assert pos==len(body)
    return dict(version=v,text=text,units=units,styles=styles,startStyle=style(12),allParagraphs=all_paragraphs,pagination=rules,pageBreaks=page_breaks,pageSettings=page_settings,running=running,paragraphs=paragraphs,links=links,linked=linked,rootLink=root_link,images=images,resources=resources)

def expected(d,tag):
    assert d['text'].endswith(tag)
    assert [p['heading'] for p in d['paragraphs'] if p['heading']]==[1,2,3,3,2]
    listed=[p for p in d['paragraphs'] if p['kind']]
    assert len(listed)==4 and [p['kind'] for p in listed]==[2,2,1,2]
    if d['version']>=7:assert all(p.get('markerTypography')=={'font':2,'size':26} for p in listed)
    assert listed[0]['item']==listed[1]['item'] and listed[2]['parent']==listed[0]['item'] and listed[3]['parent']==''
    assert len(d['links'])==1 and d['links'][0][1]=='https://example.org/urdu' and len(d['linked'])==25
    assert len(d['images'])==len(d['resources'])==1
    assert d['images'][0]['resource']==d['resources'][0]['id'] and d['images'][0]['width']==240
    assert d['resources'][0]['width']==240 and d['resources'][0]['height']==100
    assert 'blue circle and lines' in d['images'][0]['alt']
    assert d['units'][d['images'][0]['unit']]==(0xfffc,2)

def main():
    p=argparse.ArgumentParser();p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args();a.output_dir.mkdir(parents=True,exist_ok=False)
    base=['java','-cp',str(a.build_dir.resolve()/'classes')+';'+str(a.build_dir.resolve()/'lib/*'),'editor.MainKt','--local-recovery-child']
    receipts=[]
    for phase in ('mid-write','before-publish','after-publish'):
        root=(a.output_dir/phase).resolve();root.mkdir();session=str(uuid.uuid4());cmd=base+['--structured-specimen','--recovery-dir='+str(root),'--copy-session='+session,'--interrupt-at='+phase]
        child=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8');q=queue.Queue()
        def reader():
            for line in child.stdout:q.put(line)
        threading.Thread(target=reader,daemon=True).start();log=[]
        try:
            deadline=time.monotonic()+45
            while True:
                line=q.get(timeout=max(.01,deadline-time.monotonic()));log.append(line)
                if line.startswith('BARRIER:'):break
            child.kill();code=child.wait(timeout=15);assert code!=0
            target=a.output_dir/(phase+'.astra');restart=base+['--scan-copy','--recovery-dir='+str(root),'--copy-session='+session,'--copy-result='+str(target)]
            r=subprocess.run(restart,capture_output=True,text=True,encoding='utf-8',timeout=30);assert r.returncode==0,r.stderr
            decoded=decode_v6(target.read_bytes());tag='new' if phase=='after-publish' else 'old';expected(decoded,tag)
            # Retain public structural summary, not just a comparison of serializer calls.
            summary={k:v for k,v in decoded.items() if k not in ('units','text','styles','allParagraphs')};summary['textSha256']=hashlib.sha256(decoded['text'].encode('utf-16be','surrogatepass')).hexdigest()
            receipts.append(dict(pid=child.pid,command=cmd,barrier=phase,killExit=code,restartExit=r.returncode,recovered=tag,description=summary))
        finally:
            if child.poll() is None:child.kill();child.wait(timeout=10)
            child.stdout.close();child.stdin.close()
            (a.output_dir/(phase+'.log')).write_text(''.join(log),encoding='utf-8')
    (a.output_dir/'results.json').write_text(json.dumps({'status':'PASS','cases':receipts,'scope':'Actual kill of owned writer, fresh JVM recovery, independent heading/list/link/image/resource oracle; no power-loss claim.'},indent=2)+'\n',encoding='utf-8')
    print('PASS 3 structured owned-process interruption cases')
if __name__=='__main__':main()
