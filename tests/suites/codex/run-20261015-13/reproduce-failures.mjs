import {cases} from './test-public-boundary.mjs';
import {CandidateBCoordinator as Old} from '../../antigravity/run-20261015-12/candidate-b-coordinator.mjs';
for(const name of ['invalidSelection','publishedBasis','capturedCommand','immutablePublication']){try{cases[name](Old);console.log(name,'NOT REPRODUCED');process.exitCode=1;}catch(e){if(e.code!=='ERR_ASSERTION')throw e;console.log(name,'REPRODUCED',e.message);}}
