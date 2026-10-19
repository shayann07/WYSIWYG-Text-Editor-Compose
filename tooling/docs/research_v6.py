"""Read-only upstream snapshot collection. Does not execute upstream code."""
from pathlib import Path
import concurrent.futures, hashlib, json, urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'research' / 'v6-reuse-20261019'
REPOS = {
 'Darkrock-Studios/ComposeTextEditor': ['README.md','LICENSE','docs/design/text-input-sessions.md','ComposeTextEditor/src/commonMain/kotlin/com/darkrockstudios/texteditor/input/ImeEditLogic.kt','ComposeTextEditor/src/skikoMain/kotlin/com/darkrockstudios/texteditor/input/SkikoTextEditorInputMethodRequest.kt','ComposeTextEditor/src/desktopTest/kotlin/selection/PointerAffinityTest.kt'],
 'MohamedRejeb/compose-rich-editor': ['README.md','LICENSE','richeditor-compose/src/commonMain/kotlin/com/mohamedrejeb/richeditor/ui/BasicRichTextEditor.kt','richeditor-compose/src/commonMain/kotlin/com/mohamedrejeb/richeditor/paragraph/RichParagraph.kt'],
 'linreal/cascade-editor': ['README.md','LICENSE','docs/BlockSelectionOverview.md','editor/src/commonMain/kotlin/io/github/linreal/cascade/editor/ui/renderers/TextBlockField.kt'],
 'jjrodcast/TextKit': ['README.md','LICENSE','docs/table/README.md'],
 'ProseMirror/prosemirror-view': ['LICENSE','src/input.ts','src/domcoords.ts'],
 'ProseMirror/prosemirror-transform': ['LICENSE','src/map.ts'],
 'ProseMirror/prosemirror-tables': ['LICENSE','src/tablemap.ts'],
 'facebook/lexical': ['LICENSE','packages/lexical/src/LexicalSelection.ts'],
 'yjs/yjs': ['LICENSE','README.md','src/utils/UndoManager.js'],
 'y-crdt/y-crdt': ['LICENSE','README.md'],
 'automerge/automerge': ['LICENSE','README.md'],
 'ConaMobileDev/PdfKmp': ['LICENSE','README.md','pdfkmp/src/jvmMain/kotlin/com/conamobile/pdfkmp/render/JvmPdfCanvas.kt'],
 'big-jared/kmpdf': ['LICENSE','kmpdf/src/jvmMain/kotlin/io/github/bigboyapps/kmpdf/KmPdfGenerator.jvm.kt'],
 'yuroyami/KitePDF': ['LICENSE','README.md'],
 'unicode-org/icu': ['LICENSE','README.md'],
 'harfbuzz/harfbuzz': ['COPYING','README.md'],
 'JetBrains/compose-multiplatform': ['LICENSE','CHANGELOG.md'],
}

def read(url):
    request = urllib.request.Request(url, headers={'User-Agent':'Editor-v6-reuse-review','Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=40) as response:
        return response.read()

def collect(repo):
    result = {'repo':repo,'inspection':'metadata and listed source only; upstream tests NOT RUN','files':[]}
    try:
        api='https://api.github.com/repos/'+repo
        metadata=json.loads(read(api)); branch=metadata['default_branch']
        commit=json.loads(read(api+'/commits/'+branch)); sha=commit['sha']
        result.update(branch=branch,sha=sha,commitDate=commit['commit']['committer']['date'],licenseMetadata=(metadata.get('license') or {}).get('spdx_id'))
        try:
            release=json.loads(read(api+'/releases/latest'))
            result['latestReleaseMetadata']={k:release.get(k) for k in ['tag_name','published_at','prerelease','html_url']}
        except Exception as e: result['releaseError']=str(e)
        for file in REPOS[repo]:
            url=f'https://raw.githubusercontent.com/{repo}/{sha}/{file}'
            record={'path':file,'url':f'https://github.com/{repo}/blob/{sha}/{file}'}
            try:
                data=read(url); dest=OUT/'sources'/repo/file;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
                record.update(sha256=hashlib.sha256(data).hexdigest(),bytes=len(data),local=str(dest.relative_to(ROOT)))
            except Exception as e:record['error']=str(e)
            result['files'].append(record)
    except Exception as e:result['error']=str(e)
    return result

if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool: results=list(pool.map(collect,REPOS))
    (OUT/'ledger.json').write_text(json.dumps({'asOf':'2026-10-19','purpose':'v6 planning review; no dependency adopted or upstream execution','projects':results},indent=2)+'\n',encoding='utf-8')
    for result in results:
        print(result['repo'],result.get('sha'),result.get('licenseMetadata'),result.get('latestReleaseMetadata',{}).get('tag_name'),'files',sum('sha256'in x for x in result['files']),'errors',[(x['path'],x['error']) for x in result['files'] if 'error'in x])
