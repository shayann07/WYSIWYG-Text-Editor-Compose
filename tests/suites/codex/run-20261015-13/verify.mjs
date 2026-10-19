import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const dir=path.dirname(fileURLToPath(import.meta.url));
const results=[];const started=performance.now();
async function suite(name,fn) {
  const start=performance.now(),log=console.log;console.log=()=>{};
  try {const detail=await fn();results.push({name,status:'PASS',elapsedMs:performance.now()-start,detail});}
  catch(e){results.push({name,status:'FAIL',elapsedMs:performance.now()-start,error:e.stack});}
  finally {console.log=log;log(name,results.at(-1).status);if(results.at(-1).status==='FAIL')log(results.at(-1).error);}
}
const {CandidateBCoordinator:B}=await import('./candidate-b-coordinator.mjs');
const {cases,runPublicBoundaryTests,runBoundaryMutations}=await import('./test-public-boundary.mjs');
await suite('Preserved baseline counterexamples',async()=>{
  const {CandidateBCoordinator:Old}=await import('../../antigravity/run-20261015-12/candidate-b-coordinator.mjs');const observations=[];
  for(const name of ['invalidSelection','publishedBasis','capturedCommand','immutablePublication']) {
    try{cases[name](Old);observations.push({name,reproduced:false});}catch(e){observations.push({name,reproduced:e.code==='ERR_ASSERTION',message:e.message});}
  }
  if(observations.some(r=>!r.reproduced))throw Error('A claimed baseline failure did not reproduce');return observations;
});
for(const [file,fn] of [
 ['test-repaired-regressions.mjs','runRepairedRegressionsTest'],
 ['test-fixtures.mjs','runAllFixtures'],
 ['test-settlement-lifecycle.mjs','runSettlementLifecycleTests'],
 ['test-semantic-gaps.mjs','runSemanticGapsTests'],
 ['test-named-longer-sequences.mjs','runNamedLongerSequencesTest'],
 ['test-contract-coverage-sweep.mjs','runContractCoverageSweep'],
 ['test-transaction-reentrancy-contract.mjs','runTransactionReentrancyContractTests'],
 ['test-mutations.mjs','runMutationsTest']]) await suite(file,async()=>{
   const r=(await import('./'+file))[fn]();
   if(r.failed || r.CandidateB?.failed || r.IndependentOracle?.failed)throw Error(JSON.stringify(r));
   if(file==='test-contract-coverage-sweep.mjs')return {passed:r.passed,total:r.total,scope:'85 selected state/event cells, not exhaustive protocol coverage'};
   return r;
 });
await suite('Public request boundary',()=>runPublicBoundaryTests(B));
await suite('Boundary behavior mutants',runBoundaryMutations);
await suite('Versioned identity index',async()=>(await import('./test-versioned-index.mjs')).runVersionedIndexTests());
await suite('Retained bounded model exploration',async()=>{
  const {runModelChecker}=await import('./independent-model-checker.mjs');return runModelChecker(B,4);
});
await suite('Earlier run preservation',()=>{
  const hashes=JSON.parse(fs.readFileSync(path.join(dir,'baseline-hashes.json'),'utf8'));
  for(const [p,expected] of Object.entries(hashes))if(createHash('sha256').update(fs.readFileSync(p)).digest('hex')!==expected)throw Error('Changed baseline: '+p);
  return {files:Object.keys(hashes).length};
});
const sourceHashes={};for(const f of fs.readdirSync(dir).filter(f=>f.endsWith('.mjs')))sourceHashes[f]=createHash('sha256').update(fs.readFileSync(path.join(dir,f))).digest('hex');
const report={run:'run-20261015-13',timestamp:new Date().toISOString(),environment:{node:process.version,platform:process.platform,arch:process.arch},elapsedMs:performance.now()-started,passed:results.every(r=>r.status==='PASS'),results,sourceHashes,limits:['No native IME or accessibility qualification','No Kotlin implementation or allocator measurement','BFS is retained bounded sampling; new reentrant scheduling tests are a separate suite','No production performance gate passed']};
fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(report,null,2)+'\n');
console.log('RESULT',report.passed?'PASS':'FAIL');if(!report.passed)process.exitCode=1;
