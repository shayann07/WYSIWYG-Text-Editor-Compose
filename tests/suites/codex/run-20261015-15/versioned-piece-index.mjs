// Validation-only persistent B+ sequence with versioned parent links.
// Secondary maps are persistent AVL trees. Point rank is O(log^2 P), not O(log P).
const height = n => n?.h ?? 0;
function avlNode(k,v,l,r,c) { c.mapAlloc++; return Object.freeze({k,v,l,r,h:1+Math.max(height(l),height(r))}); }
function balance(k,v,l,r,c) {
  if(height(l)>height(r)+1) {
    if(height(l.l)>=height(l.r)) return avlNode(l.k,l.v,l.l,avlNode(k,v,l.r,r,c),c);
    const m=l.r;return avlNode(m.k,m.v,avlNode(l.k,l.v,l.l,m.l,c),avlNode(k,v,m.r,r,c),c);
  }
  if(height(r)>height(l)+1) {
    if(height(r.r)>=height(r.l)) return avlNode(r.k,r.v,avlNode(k,v,l,r.l,c),r.r,c);
    const m=r.l;return avlNode(m.k,m.v,avlNode(k,v,l,m.l,c),avlNode(r.k,r.v,m.r,r.r,c),c);
  }
  return avlNode(k,v,l,r,c);
}
function put(n,k,v,c) {c.mapVisits++;if(!n)return avlNode(k,v,null,null,c);if(k===n.k)return avlNode(k,v,n.l,n.r,c);return k<n.k?balance(n.k,n.v,put(n.l,k,v,c),n.r,c):balance(n.k,n.v,n.l,put(n.r,k,v,c),c);}
function del(n,k,c) {c.mapVisits++;if(!n)return null;if(k<n.k)return balance(n.k,n.v,del(n.l,k,c),n.r,c);if(k>n.k)return balance(n.k,n.v,n.l,del(n.r,k,c),c);if(!n.l)return n.r;if(!n.r)return n.l;let m=n.r;while(m.l)m=m.l;return balance(m.k,m.v,n.l,del(n.r,m.k,c),c);}
function get(n,k,c) {while(n){c.mapVisits++;if(k===n.k)return n.v;n=k<n.k?n.l:n.r;}return null;}
function floor(n,k,c) {let found=null;while(n){c.mapVisits++;if(n.k<=k){found=n;n=n.r;}else n=n.l;}return found;}
function entries(n,out=[]) {if(n){entries(n.l,out);out.push([n.k,n.v]);entries(n.r,out);}return out;}
const stats=()=>({mapVisits:0,mapAlloc:0,docVisits:0,docAlloc:0,locatorWrites:0,parentWrites:0});
export class VersionedPieceIndex {
  constructor(fanout=16) {if(fanout<4 || fanout%2)throw Error('Even fanout >=4 required');this.B=fanout;this.serial=0;}
  piece(base,length,bufferId=0,startOffset=0) {
    if(!Number.isSafeInteger(base)||!Number.isSafeInteger(length)||length<=0||!Number.isSafeInteger(base+length))throw Error('Invalid piece');
    return Object.freeze({base,length,bufferId,startOffset});
  }
  ctx(v) {return {tokens:v?.tokens??null,parents:v?.parents??null,c:stats()};}
  node(leaf,children,x) {
    const node=Object.freeze({id:++this.serial,leaf,children:Object.freeze([...children]),length:children.reduce((s,c)=>s+c.length,0)});x.c.docAlloc++;
    children.forEach((ch,index)=>{
      if(leaf){x.tokens=put(x.tokens,ch.base,Object.freeze({piece:ch,leaf:node,index}),x.c);x.c.locatorWrites++;}
      else {x.parents=put(x.parents,ch.id,Object.freeze({parent:node,index}),x.c);x.c.parentWrites++;}
    });return node;
  }
  retire(n,x) {
    x.parents=del(x.parents,n.id,x.c);
    if(n.leaf)for(const p of n.children)x.tokens=del(x.tokens,p.base,x.c);
  }
  finish(root,x) {
    while(!root.leaf && root.children.length===1){this.retire(root,x);root=root.children[0];}
    if(!root.length && !root.leaf){this.retire(root,x);root=this.node(true,[],x);}
    x.parents=del(x.parents,root.id,x.c);
    return Object.freeze({root,tokens:x.tokens,parents:x.parents,work:Object.freeze({...x.c})});
  }
  build(pieces) {
    const sorted=[...pieces].sort((a,b)=>a.base-b.base);
    for(let i=1;i<sorted.length;i++)if(sorted[i-1].base+sorted[i-1].length>sorted[i].base)throw Error('Overlapping identity');
    const x=this.ctx();let level=[];
    const partition=items=>{const count=Math.ceil(items.length/this.B);if(!count)return [[]];const out=[];let offset=0;for(let i=0;i<count;i++){const size=Math.ceil((items.length-offset)/(count-i));out.push(items.slice(offset,offset+size));offset+=size;}return out;};
    for(const group of partition(pieces))level.push(this.node(true,group,x));
    while(level.length>1)level=partition(level).map(ch=>this.node(false,ch,x));
    return this.finish(level[0],x);
  }
  rank(v,token) {
    const c=stats(),e=floor(v.tokens,token,c);
    if(!e||token>=e.v.piece.base+e.v.piece.length)return {rank:null,work:c};
    const {leaf,index,piece}=e.v;let rank=token-piece.base;
    for(let i=0;i<index;i++)rank+=leaf.children[i].length;
    let child=leaf;c.docVisits++;
    while(child!==v.root){const link=get(v.parents,child.id,c);if(!link)throw Error('Missing versioned parent');for(let i=0;i<link.index;i++)rank+=link.parent.children[i].length;child=link.parent;c.docVisits++;}
    return {rank,work:c};
  }
  insert(v,offset,piece) {
    if(!Number.isInteger(offset)||offset<0||offset>v.root.length)throw Error('Invalid offset');
    const x=this.ctx(v),overlap=floor(x.tokens,piece.base+piece.length-1,x.c);
    if(overlap && overlap.v.piece.base+overlap.v.piece.length>piece.base)throw Error('Overlapping identity');
    const visit=(n,pos)=>{
      x.c.docVisits++;let children=[...n.children];this.retire(n,x);
      if(n.leaf){let acc=0,i=0;while(i<children.length&&acc+children[i].length<=pos){acc+=children[i].length;i++;}
        if(i<children.length && pos>acc){const old=children[i],k=pos-acc;children.splice(i,1,this.piece(old.base,k,old.bufferId,old.startOffset),piece,this.piece(old.base+k,old.length-k,old.bufferId,old.startOffset+k));}
        else children.splice(i,0,piece);
      } else {let i=0,acc=0;while(i<children.length-1&&acc+children[i].length<=pos){acc+=children[i].length;i++;}children.splice(i,1,...visit(children[i],pos-acc));}
      if(children.length<=this.B)return [this.node(n.leaf,children,x)];
      const mid=Math.floor(children.length/2);return [this.node(n.leaf,children.slice(0,mid),x),this.node(n.leaf,children.slice(mid),x)];
    };
    const roots=visit(v.root,offset);return this.finish(roots.length===1?roots[0]:this.node(false,roots,x),x);
  }
  // Range deletion walks affected subtrees. Rebalance siblings locally, preserving
  // equal leaf depth; all metadata changes occur in this version's map roots.
  remove(v,start,end) {
    if(!Number.isInteger(start)||!Number.isInteger(end)||start<0||end<start||end>v.root.length)throw Error('Invalid range');
    let current=v,remaining=end-start;const work=stats();
    const accumulate=next=>{for(const k of Object.keys(work))work[k]+=next.work[k];current=next;};
    while(remaining){
      let n=current.root,offset=start,absolute=0;
      while(!n.leaf){work.docVisits++;let i=0;while(i<n.children.length-1 && offset>=n.children[i].length){offset-=n.children[i].length;absolute+=n.children[i].length;i++;}n=n.children[i];}
      let i=0;while(offset>=n.children[i].length){offset-=n.children[i].length;absolute+=n.children[i].length;i++;}
      const p=n.children[i],take=Math.min(remaining,p.length-offset);
      accumulate(this._removeChunk(current,absolute,absolute+p.length));
      if(offset)accumulate(this.insert(current,absolute,this.piece(p.base,offset,p.bufferId,p.startOffset)));
      const tail=p.length-offset-take;
      if(tail)accumulate(this.insert(current,absolute+offset,this.piece(p.base+offset+take,tail,p.bufferId,p.startOffset+offset+take)));
      remaining-=take;
    }
    return Object.freeze({...current,work:Object.freeze(work)});
  }
  _removeChunk(v,start,end) {
    if(!Number.isInteger(start)||!Number.isInteger(end)||start<0||end<start||end>v.root.length)throw Error('Invalid range');
    if(start===end)return v;const x=this.ctx(v);
    const visit=(n,a,b)=>{
      x.c.docVisits++;this.retire(n,x);let out=[],pos=0;
      if(n.leaf){for(const p of n.children){const e=pos+p.length;if(e<=a||pos>=b)out.push(p);else {const left=Math.max(0,a-pos),right=Math.min(p.length,b-pos);if(left)out.push(this.piece(p.base,left,p.bufferId,p.startOffset));if(right<p.length)out.push(this.piece(p.base+right,p.length-right,p.bufferId,p.startOffset+right));}pos=e;}}
      else {
        for(const ch of n.children){const e=pos+ch.length;if(e<=a||pos>=b)out.push(ch);else {const updated=visit(ch,Math.max(0,a-pos),Math.min(ch.length,b-pos));if(updated.length)out.push(updated);else this.retire(updated,x);}pos=e;}
        for(let i=0;out.length>1 && i<out.length;i++){
          if(out[i].children.length>=this.B/2)continue;
          const j=i===out.length-1?i-1:i, left=out[j],right=out[j+1],kids=[...left.children,...right.children];this.retire(left,x);this.retire(right,x);
          if(kids.length<=this.B){out.splice(j,2,this.node(left.leaf,kids,x));i=Math.max(-1,j-1);}
          else {const k=Math.floor(kids.length/2);out.splice(j,2,this.node(left.leaf,kids.slice(0,k),x),this.node(left.leaf,kids.slice(k),x));}
        }
      }
      return this.node(n.leaf,out,x);
    };
    return this.finish(visit(v.root,start,end),x);
  }
  flatten(v) {const out=[];const walk=n=>n.leaf?out.push(...n.children):n.children.forEach(walk);walk(v.root);return out;}
  audit(v) {
    let leavesDepth=null;const nodes=new Set(),bases=new Set();
    const walk=(n,depth,isRoot)=>{nodes.add(n.id);if(n.children.length>this.B)throw Error('Overflow');if(!isRoot&&n.children.length<this.B/2)throw Error('Underflow');if(n.leaf){if(leavesDepth===null)leavesDepth=depth;if(leavesDepth!==depth)throw Error('Unequal leaf depths');for(const p of n.children)bases.add(p.base);}else n.children.forEach(ch=>walk(ch,depth+1,false));if(n.length!==n.children.reduce((s,c)=>s+c.length,0))throw Error('Bad measure');};walk(v.root,0,true);
    const ts=entries(v.tokens),ps=entries(v.parents);
    if(ts.length!==bases.size || ps.length!==nodes.size-1)throw Error('Index retention leak');
    for(const [key,e] of ts)if(!bases.has(key)||!nodes.has(e.leaf.id))throw Error('Stale token locator');
    for(const [key,e] of ps)if(!nodes.has(key)||!nodes.has(e.parent.id))throw Error('Stale parent locator');
    return {pieces:bases.size,nodes:nodes.size,height:leavesDepth+1,tokenEntries:ts.length,parentEntries:ps.length};
  }
}

// Explicit hypothetical packed layout, NOT a measured JVM/Native heap estimate.
export function memoryEstimate({units,pieces,docNodes,parentEntries,tokenMapNodes=pieces,historyBytes=0,pendingBytes=0,tempBytes=0}) {
  const parts={textBytes:units*2,pieceScalars:pieces*24,docNodeAssumption:docNodes*256,tokenMapAssumption:tokenMapNodes*64,parentMapAssumption:parentEntries*64,historyBytes,pendingBytes,tempBytes};
  return {assumptions:'24B assumed packed piece record (20B scalar fields + padding); 256B document node; 64B AVL entry. Object headers/occupancy/platform layout require measurement.',parts,total:Object.values(parts).reduce((a,b)=>a+b,0)};
}
