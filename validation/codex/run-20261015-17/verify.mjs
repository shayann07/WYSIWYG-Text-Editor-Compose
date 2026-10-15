import fs from 'node:fs';import path from 'node:path';import {fileURLToPath} from 'node:url';import {createHash} from 'node:crypto';
import * as tests from './test-integrated.mjs';
const dir=path.dirname(fileURLToPath(import.meta.url)),results=[],begin=performance.now();
for(const name of ['namedTests','generatedIntegration','ledgerExploration','repeatedSessions','integratedMutations','scheduling']){
 const t=performance.now();try{const detail=await tests[name]();results.push({name,status:detail.passed===false?'FAIL':'PASS',detail,elapsedMs:performance.now()-t});}catch(e){results.push({name,status:'FAIL',error:e.stack});}
 const item=results.at(-1);console.log(name,item.status,JSON.stringify(item.error??(item.status==='FAIL'?item.detail:{...item.detail,cases:Array.isArray(item.detail?.cases)?item.detail.cases.length:item.detail?.cases,evidence:item.detail?.evidence?.map(e=>({case:e.case,mutant:e.mutant}))})));
}
const sourceHashes=Object.fromEntries(fs.readdirSync(dir).filter(f=>f.endsWith('.mjs')).map(f=>[f,createHash('sha256').update(fs.readFileSync(path.join(dir,f))).digest('hex')]));
const report={run:'run-20261015-17',timestamp:new Date().toISOString(),environment:{node:process.version,platform:process.platform,arch:process.arch},passed:results.every(x=>x.status==='PASS'),elapsedMs:performance.now()-begin,results,sourceHashes,limits:['One integrated reference owner, not production Kotlin','Synthetic adapter provenance only','Full-copy trees/arrays; no performance claim','Bounded traces, not a complete protocol proof']};
fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(report,null,2));
if(!report.passed){fs.writeFileSync(path.join(dir,`failure-${Date.now()}.json`),JSON.stringify(report,null,2));process.exitCode=1;}
