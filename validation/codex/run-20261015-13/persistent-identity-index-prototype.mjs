// Run-12 rebuild prototype is preserved in its original directory.
// This compatibility entry now runs the versioned-locator tests.
import {runVersionedIndexTests} from './test-versioned-index.mjs';
export function runPersistentIdentityIndexPrototypeTests(){return {passed:true,...runVersionedIndexTests()};}
if(process.argv[1]?.endsWith('persistent-identity-index-prototype.mjs'))console.log(JSON.stringify(runPersistentIdentityIndexPrototypeTests(),null,2));
