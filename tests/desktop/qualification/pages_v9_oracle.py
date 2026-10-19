"""Independent expected-state oracle for the self-authored pages specimen.

Reads bytes directly, never calls DocumentFiles or another Kotlin self-reader.
The reference text and paragraph roles below are intentionally explicit.
"""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import copy
import hashlib
import json
from pathlib import Path
import struct
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from structured_process_checks import decode_v6


def expected_pages_text():
    chapters=[]
    for number in range(1,11):
        lines=[
            f'باب {number} — تحریر، صفحات اور مطالعہ',
            'یہ خود تحریر کردہ آزمائشی عبارت ہے۔ کتاب، قلم اور تحقیق ہماری گفتگو کا موضوع ہیں۔ مُحَمَّد، قُرآن اور نَستَعلیق کے نشانات اصل صورت میں محفوظ ہیں۔',
            f'حصہ {number}: English café e\u0301, 12.5, (۲۰۲۶), example.org — اردو اور العربية لَا، می\u200cروم، ک\u200dتاب۔',
            f'فہرست {number}: مطالعہ اور سوال',
            f'English child {number} — email@example.org, [42]',
        ]
        # Atomic image and a separate empty landing paragraph; the following
        # authored separator is retained, rather than flattened away.
        if number in (2,7):lines.extend(['\ufffc',''])
        lines.append(f'اختتام {number}: یہ نمونہ انسانی لسانی و خطاطی جائزے کا متبادل نہیں۔')
        chapters.append('\n'.join(lines))
    return '\f'.join(chapters)


def check_pages(d):
    assert __debug__,'Qualification requires Python assertions enabled'
    reference=expected_pages_text()
    assert d['version']==9
    assert d['text']==reference,'Exact independent logical text differs (no normalization or mark stripping)'
    assert len(d['units'])==len(reference.encode('utf-16be'))//2
    assert d['pageSettings']==dict(paper=0,landscape=False,marginMm=14)
    assert d['pageBreaks']==[i for i,char in enumerate(reference) if char=='\f'] and len(d['pageBreaks'])==9
    assert len(d['allParagraphs'])==64
    semantic={p['index']:p for p in d['paragraphs']}
    headings=[];lists=[]
    for p,rules in zip(d['allParagraphs'],d['pagination']):
        text=d['text'][p['start']:p['end']]
        heading=text.startswith('باب ')
        assert rules==dict(breakBefore=False,keepWithNext=heading,keepLines=False,widows=2,orphans=2)
        if heading:
            headings.append(text);assert semantic[p['index']]['heading']==1
            assert p['properties']['direction']==1 and p['properties']['alignment']==0
        if text.startswith('فہرست ') or text.startswith('English child '):
            entry=semantic[p['index']];lists.append(entry)
            assert entry['kind']==(2 if text.startswith('فہرست ') else 1)
            assert p['properties']['direction']==(1 if text.startswith('فہرست ') else 2)
            assert entry['markerTypography']==dict(font=2,size=26)
    assert len(headings)==10 and len(lists)==20
    for parent,child in zip(lists[::2],lists[1::2]):
        assert parent['parent']=='' and child['parent']==parent['item'] and child['item']!=parent['item']
    assert len({p['item'] for p in lists})==20
    boundary=reference.index('باب 6 —')
    for i,(unit,style) in enumerate(zip(d['units'],d['styles'])):
        if unit[1]==0:assert style==dict(bits=0,font=2 if i<boundary else 3,size=0,color=-1)
    assert len(d['links'])==1 and d['links'][0][1]=='https://example.org/urdu'
    first=reference.index('example.org');assert d['linked']==[(i,0) for i in range(first,first+11)]
    assert len(d['images'])==2 and len(d['resources'])==1
    resource=d['resources'][0];assert resource['width']==240 and resource['height']==100 and resource['mime']=='image/png'
    for image in d['images']:
        assert d['units'][image['unit']]==(0xfffc,2)
        assert image['width']==240 and image['resource']==resource['id']
        assert image['alt']=='مقامی نیلا دائرہ اور دو لکیریں — generated illustration'
    running=d['running'];assert running['differentFirst'] and running['digits']==2
    for name,text,alignment,field in [('header','اردو تحریر — Disposable engineering specimen',0,0),('footer','صفحہ',1,2)]:
        flow=running[name]
        assert flow['text']==text and flow['direction']==1 and flow['alignment']==alignment and flow['field']==field
        assert flow['style']==dict(bits=0,font=2,size=16,color=-1)
    return dict(status='PASS',version=9,units=len(d['units']),paragraphs=len(d['allParagraphs']),headings=len(headings),listParagraphs=len(lists),explicitPageBreaks=9,images=2,resources=1,
                textUtf16Sha256=hashlib.sha256(reference.encode('utf-16be')).hexdigest(),scope='Independent exact text, inline/paragraph metadata, pagination, running flows, embedded resource dimensions/checksums; no visual or PDF claim')


def reject_mutants(d):
    """Independent oracle must reject these expected-state violations."""
    mutations={
        'omitted-mark':lambda x:x.update(text=x['text'].replace('مُحَمَّد','محمد',1)),
        'reversed-mixed-order':lambda x:x.update(text=x['text'].replace('English café','café English',1)),
        'missing-explicit-break':lambda x:x['pageBreaks'].pop(),
        'lost-keep-rule':lambda x:x['pagination'][0].update(keepWithNext=False),
        'wrong-running-field':lambda x:x['running']['footer'].update(field=1),
        'omitted-image':lambda x:x['images'].pop(),
        'lost-inline-font':lambda x:x['styles'][0].update(font=1),
    }
    for name,change in mutations.items():
        mutant=copy.deepcopy(d);change(mutant)
        try:check_pages(mutant)
        except AssertionError:continue
        raise AssertionError('Oracle missed negative control '+name)
    return list(mutations)


def decoder_selftest():
    """Hand-built bytes cover all supported versions and FF-tail consistency."""
    plain_style=struct.pack('>BBBi',0,0,0,-1);plain_paragraph=struct.pack('>BHHH',0,0,0,0)
    for version in (6,7,8,9):
        char=12 if version==9 else 10
        body=struct.pack('>III',0x41535450,version,1)+plain_style+plain_paragraph
        body+=struct.pack('>HB',char,1)+plain_style+plain_paragraph
        body+=struct.pack('>iiiii',0,0,-1,0,0)+struct.pack('>i',0)
        if version>=8:body+=bytes([0,0,14])
        if version>=9:body+=struct.pack('>i',2)+bytes([0,2,2,0,2,2])+struct.pack('>ii',1,0)+bytes([0,0,0,0])
        decoded=decode_v6(body+hashlib.sha256(body).digest())
        assert decoded['version']==version and decoded['text']==chr(char)
        assert decoded['pageBreaks']==([0] if version==9 else [])
        if version==9:
            bad=body[:-8]+struct.pack('>i',1)+body[-4:]
            try:decode_v6(bad+hashlib.sha256(bad).digest())
            except AssertionError:pass
            else:raise AssertionError('Decoder accepted wrong FF metadata index')
    return 'PASS hand-built v6/v7/v8/v9 and invalid FF index'


def main():
    p=argparse.ArgumentParser();p.add_argument('sample',nargs='?',type=Path);p.add_argument('--result',type=Path);a=p.parse_args()
    result={'decoderSelftest':decoder_selftest()}
    if a.sample:
        raw=a.sample.read_bytes();d=decode_v6(raw);result.update(check_pages(d));result['negativeControls']=reject_mutants(d);result['fileSha256']=hashlib.sha256(raw).hexdigest()
    if a.result:a.result.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=True))


if __name__=='__main__':main()
