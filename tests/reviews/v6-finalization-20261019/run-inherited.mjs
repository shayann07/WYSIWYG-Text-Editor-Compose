// Execute existing frozen test exports, writing results only beside this review.
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import * as doc from '../../validation/codex/run-20261016-20/test-document.mjs';
import * as v5 from '../../validation/codex/run-20261016-20/test-v5.mjs';
import {unicodeCorpus} from '../../validation/codex/run-20261016-20/test-unicode.mjs';
import {recoveryTests} from '../../validation/codex/run-20261016-20/test-recovery.mjs';
import {measuredHierarchyTests} from '../../validation/codex/run-20261016-20/test-measure.mjs';
import * as mutations from '../../validation/codex/run-20261016-20/test-document-mutations.mjs';
import * as integrated from '../../validation/codex/run-20261016-20/test-integrated.mjs';
import * as repairs from '../../validation/codex/run-20261016-20/test-repairs.mjs';
const groups=[['unicodeCorpus',unicodeCorpus],['recoveryTests',recoveryTests],['measuredHierarchyTests',measuredHierarchyTests],...['acceptedGeminiRegressions','independentV5Regressions'].map(n=>[n,v5[n]]),...['documentCases','inheritedDocumentCases','anchorMatrix','cellCompositionMatrix','commandCoverage'].map(n=>[n,doc[n]]),['documentMutations',mutations.documentMutations],...['reproduceBaseline','regressionTests','structuralChains','awtTests','retentionTests','retentionSoak','midStageBasis','repairMutations'].map(n=>[n,repairs[n]]),...['namedTests','generatedIntegration','ledgerExploration','repeatedSessions','integratedMutations','scheduling'].map(n=>[n,integrated[n]])];
const results=[];
for(const [name,fn]of groups){try{const detail=await fn();results.push({name,status:detail.passed===false?'FAIL':'PASS',detail});}catch(e){results.push({name,status:'FAIL',error:e.stack});}console.log(results.at(-1).status,name);}
const passed=results.every(x=>x.status==='PASS');const report={passed,runtime:process.version,results,sourceSha256:createHash('sha256').update(fs.readFileSync(import.meta.filename)).digest('hex'),scope:'Existing frozen models unchanged; not production or native qualification'};
const output=process.argv.find(x=>x.startsWith('--output='))?.slice(9);
if(output)fs.writeFileSync(path.resolve(output),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
else if(!fs.existsSync(new URL('../../docs/plans/v6/v6-freeze-record.json',import.meta.url)))fs.writeFileSync(new URL('inherited-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
else console.log('Sealed evidence preserved; use --output=<new-file> to save this execution.');
if(!passed)process.exitCode=1;
