// Private local owner pipe for C0 experiments. Always close the owned child.
import {spawn} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

// Capture from the frame that supplied the target. Never silently refresh an old request's basis.
export function canonicalBasis(frame) {
  if (typeof frame.ownerIncarnation !== 'string' || !Array.isArray(frame.committedUnitIds) ||
      frame.committedUnitIds.some(id => typeof id !== 'string') || !Number.isSafeInteger(frame.revision) || !Number.isSafeInteger(frame.epoch) ||
      typeof frame.identityGeneration !== 'string' || !/^(0|[1-9][0-9]*)$/.test(frame.identityGeneration) || frame.identityGeneration.length > 19 || BigInt(frame.identityGeneration) > 9223372036854775807n)
    throw new Error('Missing exact owner identity basis');
  return {ownerIncarnation: frame.ownerIncarnation, basisRevision: frame.revision,
    basisUnitIds: [...frame.committedUnitIds], basisIdentityGeneration: frame.identityGeneration, epoch: frame.epoch};
}

export class JvmOwnerClient {
  constructor(build, output, root) {
    this.output = output; fs.mkdirSync(output, {recursive: true});
    this.command = ['java', '-cp', path.join(build, 'classes') + path.delimiter + path.join(build, 'lib/*'), 'editor.MainKt', '--web-owner-probe'];
    this.child = spawn(this.command[0], this.command.slice(1), {cwd: root, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
    this.pending = new Map(); this.serial = 0;
    this.exit = new Promise(resolve => this.child.once('exit', (code, signal) => {this.termination = {code, signal}; resolve();}));
    this.ready = new Promise((resolve, reject) => {
      this.rejectReady = reject;
      this.startup = setTimeout(() => reject(new Error('Owner startup timeout')), 10000);
      this.child.once('error', reject);
      this.lines = readline.createInterface({input: this.child.stdout});
      this.lines.on('line', line => {
        fs.appendFileSync(path.join(output, 'responses.jsonl'), line + '\n');
        try {
          const value = JSON.parse(line);
          if (value.status === 'READY') {this.isReady = true; clearTimeout(this.startup); resolve(value); return;}
          const waiter = this.pending.get(value.id);
          if (waiter) {clearTimeout(waiter.timer); this.pending.delete(value.id); waiter.resolve(value);}
        } catch (error) {reject(error);}
      });
    });
    this.child.stderr.on('data', data => fs.appendFileSync(path.join(output, 'stderr.log'), data));
    this.child.on('exit', code => {clearTimeout(this.startup); if (!this.isReady) this.rejectReady(new Error('Owner exited before READY: ' + code)); for (const waiter of this.pending.values()) {clearTimeout(waiter.timer); waiter.reject(new Error('Owner exited: ' + code));} this.pending.clear();});
    this.child.stdin.on('error', () => {});
  }
  async request(payload) {
    await this.ready;
    if (this.pending.size >= 256) throw new Error('Bounded owner request queue exceeded');
    const id = ++this.serial;
    fs.appendFileSync(path.join(this.output, 'requests.jsonl'), JSON.stringify({...payload, id}) + '\n');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {this.pending.delete(id); reject(new Error('Owner request timeout'));}, 10000);
      this.pending.set(id, {resolve, reject, timer});
      this.child.stdin.write(JSON.stringify({...payload, id}) + '\n', error => {if (error) {clearTimeout(timer); this.pending.delete(id); reject(error);}});
    });
  }
  async close() {
    clearTimeout(this.startup);
    if (!this.termination) {try {await this.request({command: 'shutdown'});} catch {} this.child.stdin.end();}
    let timer;
    if (!this.termination) {
      await Promise.race([this.exit, new Promise(resolve => {timer = setTimeout(resolve, 3000);})]); clearTimeout(timer);
    }
    if (!this.termination) {
      this.child.kill();
      await Promise.race([this.exit, new Promise(resolve => {timer = setTimeout(resolve, 3000);})]); clearTimeout(timer);
    }
    this.lines?.close();
    for (const waiter of this.pending.values()) {clearTimeout(waiter.timer); waiter.reject(new Error('Owner closed'));} this.pending.clear();
    return this.termination ?? {code: null, signal: null, failedToClose: true};
  }
}
