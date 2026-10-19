import fs from 'node:fs';import path from 'node:path';import {fileURLToPath} from 'node:url';import {createHash} from 'node:crypto';
import * as tests from './test-contracts.mjs';
const dir=path.dirname(fileURLToPath(import.meta.url)),results=[],begin=performance.now();
for(const name of ['structuralTests','overlayTests','generatedStructuralTests','admissionTests','historyTests','historyExploration','mutations']){
  const t=performance.now();try{const detail=tests[name]();results.push({name,status:'PASS',detail,elapsedMs:performance.now()-t});}catch(e){results.push({name,status:'FAIL',error:e.stack});}
  console.log(name,results.at(-1).status,JSON.stringify(results.at(-1).detail??results.at(-1).error));
}
const sourceHashes=Object.fromEntries(fs.readdirSync(dir).filter(f=>f.endsWith('.mjs')).map(f=>[f,createHash('sha256').update(fs.readFileSync(path.join(dir,f))).digest('hex')]));
const report={run:'run-20261015-16',timestamp:new Date().toISOString(),environment:{node:process.version,platform:process.platform,arch:process.arch},passed:results.every(x=>x.status==='PASS'),elapsedMs:performance.now()-begin,results,sourceHashes,limits:['Array/tree-copy reference model only','Admission and history models are separately tested contracts, not integrated OS adapters','Not exhaustive model checking','No production or platform qualification']};
fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(report,null,2));if(!report.passed)process.exitCode=1;
