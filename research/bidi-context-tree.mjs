// Research candidate, deliberately restricted to one isolating run sequence,
// fixed base direction, L/R/ON/WS characters and ASCII paired brackets.
// No claim of full Unicode conformance or novelty. No imported baseline code.
export class BidiContextTree {
  constructor(text, base = 0) {
    if (!/^[a\u05d0 .()[\]{}]*$/u.test(text)) throw Error('unsupported character');
    if (base !== 0 && base !== 1) throw Error('unsupported base');
    this.text = [...text]; this.base = base; this.visits = 0; this.repairs = 0;
    this.leaves = new Map(); this.rebuild();
  }
  rebuild() {
    const stack = [], pairs = new Map();
    const opens = '([{', closes = ')]}';
    for (let i = 0; i < this.text.length; i++) {
      const c = this.text[i];
      if (opens.includes(c)) {
        if (stack.length === 63) break;
        stack.push(i);
      } else if (closes.includes(c)) {
        const wanted = opens[closes.indexOf(c)];
        for (let j = stack.length - 1; j >= 0; j--) {
          if (this.text[stack[j]] === wanted) {
            pairs.set(stack[j], i); stack.length = j; break;
          }
        }
      }
    }
    this.leaves.clear();
    const makeSeq = nodes => {
      if (!nodes.length) return null;
      const build = (lo,hi) => {
        if (hi - lo === 1) return nodes[lo];
        const mid = (lo + hi) >>> 1;
        return this.make({kind:'seq', left:build(lo,mid), right:build(mid,hi)});
      };
      return build(0,nodes.length);
    };
    const region = (lo,hi) => {
      const nodes = [];
      for (let i = lo; i < hi;) {
        if (pairs.has(i)) {
          const end = pairs.get(i);
          const inside = region(i+1,end);
          nodes.push(this.make({kind:'pair', start:i, end:end+1, inside}));
          i = end+1;
        } else {
          const leaf = this.make({kind:'leaf', start:i, end:i+1, char:this.text[i]});
          this.leaves.set(i,leaf); nodes.push(leaf); i++;
        }
      }
      return makeSeq(nodes);
    };
    this.root = region(0,this.text.length);
  }
  make(n) {
    n.parent = null;
    if(n.kind === 'seq') {
      n.left.parent = n.right.parent = n;
      n.start = n.left.start; n.end = n.right.end;
    } else if (n.inside) n.inside.parent = n;
    this.refresh(n); return n;
  }
  refresh(n) {
    this.repairs++;
    n.out = [0,1]; n.first = [-1,-1]; n.last = [-1,-1]; n.events = 0;
    if(n.kind === 'leaf') {
      n.mask = n.char === 'a' ? 1 : n.char === '\u05d0' ? 2 : 0;
      if(n.mask) {
        const dir = n.mask === 1 ? 0 : 1;
        n.out = [dir,dir]; n.first = [dir,dir]; n.last = [dir,dir]; n.events = 1;
      }
    } else if(n.kind === 'seq') {
      const a = n.left, b = n.right;
      n.mask = a.mask | b.mask; n.events = a.events+b.events;
      for(let q=0;q<2;q++) {
        const mid=a.out[q]; n.out[q] = b.out[mid];
        n.first[q] = a.first[q] >= 0 ? a.first[q] : b.first[mid];
        n.last[q] = b.last[mid] >= 0 ? b.last[mid] : a.last[q];
      }
    } else {
      n.mask = n.inside?.mask ?? 0;
      if(n.mask) {
        n.events = (n.inside?.events ?? 0)+2;
        for(let q=0;q<2;q++) {
          const direction = n.mask & (1 << this.base) ? this.base : q;
          n.out[q] = n.first[q] = n.last[q] = direction;
        }
      }
    }
  }
  // Only topology-preserving, one-scalar substitutions are accelerated here.
  replace(index, char) {
    if (!Number.isInteger(index) || index < 0 || index >= this.text.length) throw Error('range');
    if (!/^[a\u05d0 .]$/u.test(char)) throw Error('replacement changes bracket topology');
    let node = this.leaves.get(index);
    if(!node || !/^[a\u05d0 .]$/u.test(node.char)) throw Error('replacement changes bracket topology');
    this.text[index] = char; node.char = char;
    for(;node;node=node.parent) this.refresh(node);
  }
  // Returns the first and last resolved strong event in the queried interval.
  // q is the context entering this complete subtree, even for partial queries.
  range(node, lo, hi, q) {
    this.visits++;
    if(!node || hi <= node.start || lo >= node.end) return [-1,-1];
    if(lo <= node.start && hi >= node.end) return [node.first[q],node.last[q]];
    if(node.kind === 'leaf') return [node.first[q],node.last[q]];
    const join=(a,b)=>[a[0]>=0?a[0]:b[0], b[1]>=0?b[1]:a[1]];
    if(node.kind === 'seq') return join(
      this.range(node.left,lo,hi,q),
      this.range(node.right,lo,hi,node.left.out[q]));
    const direction = node.mask ? node.out[q] : -1;
    const entry = direction >= 0 ? direction : q;
    let result = lo <= node.start && hi > node.start ? [direction,direction] : [-1,-1];
    result = join(result,this.range(node.inside,lo,hi,entry));
    if(lo <= node.end-1 && hi > node.end-1) result = join(result,[direction,direction]);
    return result;
  }
  levelAt(i) {
    if (!Number.isInteger(i) || i < 0 || i >= this.text.length) throw Error('range');
    let direction = this.range(this.root,i,i+1,this.base)[0];
    if(direction < 0) {
      let before=this.range(this.root,0,i,this.base)[1];
      let after=this.range(this.root,i+1,this.text.length,this.base)[0];
      if(before < 0) before=this.base;
      if(after < 0) after=this.base;
      direction=before===after?before:this.base;
    }
    return this.base + (direction === this.base ? 0 : 1);
  }
  levels() { return this.text.map((_,i)=>this.levelAt(i)); }
}
