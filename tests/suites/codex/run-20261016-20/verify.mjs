import fs from 'node:fs';import path from 'node:path';import {fileURLToPath} from 'node:url';import {createHash} from 'node:crypto';
import * as doc from './test-document.mjs';
import * as v5 from './test-v5.mjs';
import {unicodeCorpus} from './test-unicode.mjs';
import {recoveryTests} from './test-recovery.mjs';
import {measuredHierarchyTests} from './test-measure.mjs';
import * as mutations from './test-document-mutations.mjs';
import * as integrated from './test-integrated.mjs';
import * as repairs from './test-repairs.mjs';
const dir=path.dirname(fileURLToPath(import.meta.url)),results=[],begin=performance.now();
// Imported reviewer files and historical evidence are immutable inputs.
const root=path.resolve(dir,'../../..');const inputs=JSON.parse(fs.readFileSync(path.join(dir,'gemini-input-hashes.json'),'utf8'));
for(const [file,digest]of Object.entries(inputs)){if(createHash('sha256').update(fs.readFileSync(path.join(root,file.replaceAll('\\','/')))).digest('hex')!==digest)throw Error('ReviewInputChanged:'+file);}
for(const [name,fn] of [['unicodeCorpus',unicodeCorpus],['recoveryTests',recoveryTests],['measuredHierarchyTests',measuredHierarchyTests],...['acceptedGeminiRegressions','independentV5Regressions'].map(n=>[n,v5[n]]),...['documentCases','inheritedDocumentCases','anchorMatrix','cellCompositionMatrix','commandCoverage'].map(n=>[n,doc[n]]),['documentMutations',mutations.documentMutations],...['reproduceBaseline','regressionTests','structuralChains','awtTests','retentionTests','retentionSoak','midStageBasis','repairMutations'].map(n=>[n,repairs[n]]),...['namedTests','generatedIntegration','ledgerExploration','repeatedSessions','integratedMutations','scheduling'].map(n=>[n,integrated[n]])]){const t=performance.now();try{const detail=await fn();results.push({name,status:detail.passed===false?'FAIL':'PASS',detail,elapsedMs:performance.now()-t});}catch(e){results.push({name,status:'FAIL',error:e.stack});}const item=results.at(-1);console.log(name,item.status,JSON.stringify(item.error??(item.status==='FAIL'?item.detail:{cases:Array.isArray(item.detail.cases)?item.detail.cases.length:item.detail.cases,commands:item.detail.rows?.length,killed:item.detail.killed})));}
const sourceHashes=Object.fromEntries(fs.readdirSync(dir).filter(f=>f.endsWith('.mjs')).map(f=>[f,createHash('sha256').update(fs.readFileSync(path.join(dir,f))).digest('hex')]));
const report={run:'run-20261016-20',timestamp:new Date().toISOString(),environment:{node:process.version,platform:process.platform,arch:process.arch},passed:results.every(x=>x.status==='PASS'),elapsedMs:performance.now()-begin,results,sourceHashes,limits:['Whole-document reference, not production code','Synthetic input; pinned Unicode 16 graphemes, runtime word segmentation','Full-copy trees, not production complexity or heap proof']};fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(report,null,2));if(!report.passed){fs.writeFileSync(path.join(dir,`failure-${Date.now()}.json`),JSON.stringify(report,null,2));process.exitCode=1;}
