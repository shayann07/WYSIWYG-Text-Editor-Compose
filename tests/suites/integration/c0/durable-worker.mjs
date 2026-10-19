// One owned process per request, so fault injection genuinely closes the SQLite connection.
import fs from 'node:fs';
import {DurableAuthority} from './durable-authority.mjs';
const option = key => process.argv.find(a => a.startsWith('--' + key + '='))?.slice(key.length + 3);
const store = new DurableAuthority(option('database'), {checkpointCodec: option('checkpoint-codec') ?? 'v8'});
const injection = process.argv.includes('--hold-before-commit') ? () => {
  fs.writeSync(1, JSON.stringify({status: 'TRANSACTION_HELD', pid: process.pid}) + '\n');
  const signal = Buffer.alloc(1);
  // Parent owns this pipe. EOF/abort exits before COMMIT; no polling/timing race.
  if (fs.readSync(0, signal, 0, 1, null) !== 1 || signal[0] !== 67) process.exit(83);
} : option('inject');
try {
  const result = process.argv.includes('--compact') ? store.compact(injection)
    : store.accept(JSON.parse(fs.readFileSync(option('event'), 'utf8')), injection);
  process.stdout.write(JSON.stringify({result, snapshot: store.snapshot()}) + '\n');
} finally {store.close();}
