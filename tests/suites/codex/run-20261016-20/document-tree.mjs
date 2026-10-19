// Full-copy semantic reference utilities. No production data structure claims.
import {clone,index,leaves,project,raw,validateTree,reconstruct} from './structural-model.mjs';
export {clone,index,leaves,project,raw};
export const isText=n=>['paragraph','heading','code'].includes(n?.type);
export const isTable=n=>n?.type==='object'&&n.props?.kind==='table';
export const err=code=>{throw Error(code);};
export function makeDocument(body){return {id:'document',type:'document',props:{resources:{},annotations:[]},children:[clone(body)]};}
export function flows(doc){
  const out=new Map();
  function walk(f,owner=null){if(out.has(f.id))err('DuplicateDocumentId');out.set(f.id,{flow:f,owner});for(const n of leaves(f))if(isTable(n))for(let r=0;r<n.props.rows.length;r++)for(let col=0;col<n.props.rows[r].cells.length;col++){const cell=n.props.rows[r].cells[col];walk(cell.flow,{table:n.id,row:r,col,cell:cell.id,parentFlow:f.id});}}
  walk(doc.children[0]);return out;
}
export function getFlow(doc,id){return flows(doc).get(id)?.flow??err('FlowDeleted');}
export function putFlow(doc,id,value){const map=flows(doc),found=map.get(id);if(!found)err('FlowDeleted');if(!found.owner)doc.children[0]=value;else{const owner=map.get(found.owner.parentFlow).flow,table=index(owner).get(found.owner.table).node;table.props.rows[found.owner.row].cells[found.owner.col].flow=value;}return doc;}
export function locate(doc,id){for(const {flow}of flows(doc).values()){const found=index(flow).get(id);if(found)return {...found,flowId:flow.id,flow};}return null;}
export function locateAtom(doc,id){for(const {flow}of flows(doc).values())for(const block of leaves(flow)){const offset=block.units?.findIndex(u=>u.kind==='inlineAtom'&&u.nodeId===id);if(offset>=0)return {node:block.units[offset],block,offset,inline:true,flow,flowId:flow.id};}return null;}
export function validateDocument(doc){
  const ids=new Set([doc.id]),tokens=new Set();
  for(const {flow,owner}of flows(doc).values()){
    validateTree(flow);
    for(const [id,{node}]of index(flow)){if(ids.has(id))err('DuplicateDocumentId');ids.add(id);if(isTable(node)){
      if(owner)err('NestedTablesUnsupported');const rows=node.props.rows;if(!Array.isArray(rows)||!rows.length)err('InvalidTable');const width=rows[0].cells?.length;
      if(!width)err('InvalidTable');for(const row of rows){if(ids.has(row.id))err('DuplicateDocumentId');ids.add(row.id);if(row.cells.length!==width)err('NonRectangularTable');for(const cell of row.cells){if(ids.has(cell.id))err('DuplicateDocumentId');ids.add(cell.id);}}
    }}
    for(const u of project(flow)){if(tokens.has(u.tokenId))err('DuplicateDocumentToken');tokens.add(u.tokenId);if(u.kind==='inlineAtom'){if(ids.has(u.nodeId))err('DuplicateDocumentId');ids.add(u.nodeId);}}
    for(const n of leaves(flow)){if(n.props?.resourceId&&!Object.hasOwn(doc.props.resources,n.props.resourceId))err('InvalidResourceReference');for(const u of n.units??[])if(u.props?.resourceId&&!Object.hasOwn(doc.props.resources,u.props.resourceId))err('InvalidResourceReference');}
  }
  for(const a of doc.props.annotations??[]){if(ids.has(a.id))err('DuplicateDocumentId');ids.add(a.id);validatePoint(doc,a.anchor);validatePoint(doc,a.focus);}
  return true;
}
export function leafRanges(flow){let offset=0;return leaves(flow).map((n,i)=>{if(i)offset++;const start=offset;offset+=isText(n)?n.units.length:1;return {id:n.id,start,end:offset,node:n};});}
export function pointOffset(flow,point){
  const ranges=leafRanges(flow);
  if(point.kind==='gap'){
    const parent=index(flow).get(point.parentId)?.node;if(!parent?.children||!Number.isInteger(point.index)||point.index<0||point.index>parent.children.length)err('InvalidGap');
    if(point.index<parent.children.length){const id=leaves(parent.children[point.index])[0].id;return ranges.find(x=>x.id===id).start;}
    const id=leaves(parent).at(-1).id;return ranges.find(x=>x.id===id).end;
  }
  const r=ranges.find(x=>x.id===point.blockId);if(!r||!isText(r.node)||!Number.isSafeInteger(point.offset)||point.offset<0||point.offset>r.node.units.length)err('InvalidPoint');return r.start+point.offset;
}
export function validatePoint(doc,p){if(!p||!['text','gap'].includes(p.kind)||typeof p.flowId!=='string')err('InvalidPoint');pointOffset(getFlow(doc,p.flowId),p);return true;}
export function pointAt(flow,offset,association='after'){
  const ranges=leafRanges(flow);if(!Number.isSafeInteger(offset)||offset<0||offset>raw(project(flow)).length)err('InvalidPoint');
  let r=ranges.find(x=>offset>=x.start&&offset<=x.end&&isText(x.node));
  if(!r){r=association==='before'?[...ranges].reverse().find(x=>isText(x.node)&&x.end<=offset):ranges.find(x=>isText(x.node)&&x.start>=offset);r??=ranges.find(x=>isText(x.node));}
  return {kind:'text',flowId:flow.id,blockId:r.id,offset:Math.max(0,Math.min(r.node.units.length,offset-r.start))};
}
export function gapMap(before,after,pos,association='after'){
  const rank=new Map(after.map((u,i)=>[u.tokenId,i]));let l=pos-1,r=pos;
  const lostLeft=l>=0&&!rank.has(before[l].tokenId),lostRight=r<before.length&&!rank.has(before[r].tokenId);
  while(l>=0&&!rank.has(before[l].tokenId))l--;while(r<before.length&&!rank.has(before[r].tokenId))r++;
  const left=l<0?0:rank.get(before[l].tokenId)+1,right=r>=before.length?after.length:rank.get(before[r].tokenId);
  return {offset:association==='before'?left:right,deleted:lostLeft&&lostRight};
}
export function mapPoint(point,beforeDoc,afterDoc,association='after'){
  const oldFlows=flows(beforeDoc),newFlows=flows(afterDoc),old=oldFlows.get(point.flowId);if(!old)err('InvalidPoint');
  if(point.kind!=='gap'){
    const moved=locate(afterDoc,point.blockId),original=index(old.flow).get(point.blockId)?.node;
    if(moved&&isText(original)&&isText(moved.node)&&original.units.length===moved.node.units.length&&original.units.every((u,i)=>u.tokenId===moved.node.units[i].tokenId)){
      const oldIds=leaves(old.flow).map(n=>n.id),newIds=leaves(moved.flow).map(n=>n.id),oldCommon=oldIds.filter(id=>newIds.includes(id)),newCommon=newIds.filter(id=>oldIds.includes(id));
      if(moved.flowId!==point.flowId||index(old.flow).get(point.blockId).parent!==moved.parent||oldCommon.indexOf(point.blockId)!==newCommon.indexOf(point.blockId))return {point:{...point,flowId:moved.flowId},deleted:false};
    }
    if(moved&&moved.flowId!==point.flowId&&isText(original)&&isText(moved.node)){const mapped=gapMap(original.units,moved.node.units,point.offset,association);return {point:{...point,flowId:moved.flowId,offset:mapped.offset},deleted:mapped.deleted};}
  }
  if(!newFlows.has(point.flowId)){
    const owner=old.owner,table=owner&&locate(afterDoc,owner.table);
    if(table&&isTable(table.node)){const rows=table.node.props.rows,row=Math.min(owner.row,rows.length-1),col=Math.min(owner.col,rows[row].cells.length-1),f=rows[row].cells[col].flow;return {point:pointAt(f,0),deleted:true};}
    const parentOld=owner?getFlow(beforeDoc,owner.parentFlow):old.flow,parentNew=getFlow(afterDoc,afterDoc.children[0].id);
    const where=owner?leafRanges(parentOld).find(x=>x.id===owner.table)?.start??0:0;
    const mapped=gapMap(project(parentOld),project(parentNew),where,association);return {point:pointAt(parentNew,mapped.offset,association),deleted:true};
  }
  const now=newFlows.get(point.flowId).flow;
  if(point.kind==='gap'){
    const oldParent=index(old.flow).get(point.parentId)?.node,newParent=index(now).get(point.parentId)?.node;
    if(newParent?.children){const left=oldParent.children[point.index-1]?.id,right=oldParent.children[point.index]?.id;let i=association==='before'?newParent.children.findIndex(n=>n.id===left)+1:newParent.children.findIndex(n=>n.id===right);
      if(association==='before'&&!left)i=0;if(association==='after'&&!right)i=newParent.children.length;if(i>=0&&i<=newParent.children.length)return {point:{...point,index:i},deleted:false};}
  }
  const offset=pointOffset(old.flow,point),mapped=gapMap(project(old.flow),project(now),offset,association);
  return {point:pointAt(now,mapped.offset,association),deleted:mapped.deleted};
}
export function patchAttrs(attrs,patch){const result=clone(attrs??{});for(const [key,value]of Object.entries(patch)){if(value===null)delete result[key];else result[key]=clone(value);}return result;}
export function textUnits(text,allocate,attrs={}){return text.split('').map(char=>({kind:'text',tokenId:allocate('unit'),char,attrs:clone(attrs),originSessionId:null}));}
export function unitStops(units,granularity='grapheme'){
  const stops=new Set([0,units.length]);let a=0;
  while(a<units.length){if(units[a].kind==='inlineAtom'){stops.add(a);stops.add(++a);continue;}let b=a;while(b<units.length&&units[b].kind!=='inlineAtom')b++;for(const g of new Intl.Segmenter('en',{granularity}).segment(raw(units.slice(a,b)))){stops.add(a+g.index);stops.add(a+g.index+g.segment.length);}a=b;}
  return [...stops].sort((a,b)=>a-b);
}
export function userRange(flow,range,affinity='after'){
  const stops=new Set([0,project(flow).length]);for(const r of leafRanges(flow)){
    stops.add(r.start);stops.add(r.end);if(!isText(r.node))continue;let a=0;
    while(a<r.node.units.length){if(r.node.units[a].kind==='inlineAtom'){stops.add(r.start+a);stops.add(r.start+a+1);a++;continue;}let b=a;while(b<r.node.units.length&&r.node.units[b].kind!=='inlineAtom')b++;for(const g of new Intl.Segmenter('en',{granularity:'grapheme'}).segment(raw(r.node.units.slice(a,b)))){stops.add(r.start+a+g.index);stops.add(r.start+a+g.index+g.segment.length);}a=b;}
  }
  const all=[...stops].sort((a,b)=>a-b),floor=x=>all.filter(p=>p<=x).at(-1),ceil=x=>all.find(p=>p>=x);
  return range[0]===range[1]?[affinity==='before'?floor(range[0]):ceil(range[0]),affinity==='before'?floor(range[0]):ceil(range[0])]:[floor(Math.min(...range)),ceil(Math.max(...range))];
}
export function paragraph(allocate,text='',attrs={}){return {id:allocate('paragraph'),type:'paragraph',props:{},units:textUnits(text,allocate,attrs),emptyBlockAttributes:clone(attrs)};}
export function normalize(root,allocate){
  function prune(n){if(!n.children)return n;n.children=n.children.map(prune).filter(Boolean);return n.children.length?n:null;}
  const p=prune(root);if(!p)return {...root,children:[paragraph(allocate)]};if(!leaves(p).some(isText))p.children.push(paragraph(allocate));return p;
}
export function spliceFlow(flow,a,b,units,seed={}){const tape=project(flow);if(a<0||b<a||b>tape.length)err('InvalidTargetRange');return reconstruct(flow,[...tape.slice(0,a),...units,...tape.slice(b)],seed);}
export function splitBlock(flow,blockId,offset,allocate,{rightType=null,rightProps=null}={}){
  const ix=index(flow),entry=ix.get(blockId);if(!isText(entry?.node)||offset<0||offset>entry.node.units.length)err('InvalidSplit');
  const n=entry.node,right={...clone(n),id:allocate('block'),type:rightType??n.type,props:clone(rightProps??n.props),units:n.units.slice(offset)};n.units=n.units.slice(0,offset);
  const parent=ix.get(entry.parent).node,k=parent.children.findIndex(x=>x.id===blockId);parent.children.splice(k+1,0,right);return right.id;
}
export function nearestItem(flow,id){const ix=index(flow);let e=ix.get(id);while(e?.parent){e=ix.get(e.parent);if(e?.node.type==='item')return e.node.id;}return null;}
export function liftItem(flow,itemId,allocate){
  const ix=index(flow),item=ix.get(itemId)?.node,listEntry=ix.get(ix.get(itemId)?.parent),list=listEntry?.node;if(!item||list?.type!=='list')err('InvalidListItem');
  const i=list.children.findIndex(n=>n.id===itemId),before=list.children.slice(0,i),tail=list.children.slice(i+1),parent=ix.get(listEntry.parent)?.node;
  if(parent?.type==='item'){
    const outer=ix.get(ix.get(parent.id).parent)?.node;if(outer?.type!=='list')err('InvalidListItem');const k=parent.children.findIndex(n=>n.id===list.id),following=parent.children.slice(k+1);parent.children=parent.children.slice(0,k);
    if(before.length){list.children=before;parent.children.push(list);}
    if(tail.length)item.children.push({...clone(list),id:before.length?allocate('list'):list.id,children:tail});
    item.children.push(...following);outer.children.splice(outer.children.findIndex(n=>n.id===parent.id)+1,0,item);
  }else{
    const at=parent.children.findIndex(n=>n.id===list.id),replacement=[];
    if(before.length)replacement.push({...list,children:before});replacement.push(...item.children);
    if(tail.length)replacement.push({...list,id:before.length?allocate('list'):list.id,props:{...list.props,...(list.props.kind==='ordered'?{start:(list.props.start??1)+i+1}:{})},children:tail});
    parent.children.splice(at,1,...replacement);
  }
  return normalize(flow,allocate);
}
export function indentItem(flow,itemId,allocate){const ix=index(flow),item=ix.get(itemId)?.node,list=ix.get(ix.get(itemId)?.parent)?.node;if(list?.type!=='list')err('InvalidListItem');const i=list.children.findIndex(n=>n.id===itemId);if(i===0)err('NoPreviousListItem');const previous=list.children[i-1];list.children.splice(i,1);let nested=previous.children.at(-1);if(nested?.type!=='list'||nested.props.kind!==list.props.kind){nested={id:allocate('list'),type:'list',props:clone(list.props),children:[]};previous.children.push(nested);}nested.children.push(item);return flow;}
export function splitItem(flow,blockId,offset,allocate){
  const itemId=nearestItem(flow,blockId);if(!itemId)err('NotInList');const rightId=splitBlock(flow,blockId,offset,allocate);let ix=index(flow),right=ix.get(rightId).node,parent=ix.get(ix.get(rightId).parent).node;
  parent.children.splice(parent.children.findIndex(n=>n.id===rightId),1);
  let childId=blockId;
  while(parent.id!==itemId){const k=parent.children.findIndex(n=>n.id===childId),trailing=parent.children.splice(k+1);right={...clone(parent),id:allocate(parent.type),children:[right,...trailing]};childId=parent.id;parent=ix.get(ix.get(parent.id).parent).node;}
  const k=parent.children.findIndex(n=>n.id===childId),trailing=parent.children.splice(k+1),newItem={...clone(parent),id:allocate('item'),children:[right,...trailing]};
  const list=ix.get(ix.get(parent.id).parent).node;list.children.splice(list.children.findIndex(n=>n.id===parent.id)+1,0,newItem);return {flow,rightId};
}
export function cloneFresh(node,allocate,links=new Map(),codes=new Map()){
  const n=clone(node);function visit(x){x.id=allocate(x.type??'node');if(x.units)for(const u of x.units){u.tokenId=allocate('unit');u.originSessionId=null;if(u.kind==='inlineAtom')u.nodeId=allocate('atom');if(u.attrs?.link){const id=u.attrs.link.id;if(!links.has(id))links.set(id,allocate('link'));u.attrs.link={...u.attrs.link,id:links.get(id)};}if(u.attrs?.code?.id){const id=u.attrs.code.id;if(!codes.has(id))codes.set(id,allocate('codeRun'));u.attrs.code={...u.attrs.code,id:codes.get(id)};}}
    for(const c of x.children??[])visit(c);if(isTable(x))for(const row of x.props.rows){row.id=allocate('row');for(const cell of row.cells){cell.id=allocate('cell');visit(cell.flow);}}}
  visit(n);return n;
}
export function closedSlice(flow,a,b){
  const records=new Map();for(const r of leafRanges(flow)){
    if(isText(r.node)){const lo=Math.max(0,a-r.start),hi=Math.min(r.node.units.length,b-r.start);if(lo<hi||r.node.units.length===0&&a<=r.start-1&&b>=r.start)records.set(r.id,{...clone(r.node),units:clone(r.node.units.slice(lo,hi))});}
    else if(a<=r.start&&b>=r.end)records.set(r.id,clone(r.node));
  }
  const visit=n=>{if(!n.children)return records.get(n.id)??null;const children=n.children.map(visit).filter(Boolean);return children.length?{...clone(n),children}:null;};return visit(flow)?.children??[];
}
export function cellFlow(allocate){return {id:allocate('flow'),type:'flow',props:{},children:[paragraph(allocate)]};}
export function table(allocate,rows,cols){if(!Number.isSafeInteger(rows)||!Number.isSafeInteger(cols)||rows<1||cols<1||rows*cols>(allocate.maxCells??4096))err('TableAdmissionLimit');return {id:allocate('table'),type:'object',props:{kind:'table',rows:Array.from({length:rows},()=>({id:allocate('row'),cells:Array.from({length:cols},()=>({id:allocate('cell'),flow:cellFlow(allocate)}))}))}};}
export function parseTSV(text){const rows=[[]];let field='',quoted=false;for(let i=0;i<text.length;i++){const ch=text[i];if(ch==='"'){if(quoted&&text[i+1]==='"'){field+='"';i++;}else if(quoted)quoted=false;else if(field==='')quoted=true;else field+=ch;}else if(!quoted&&(ch==='\t'||ch==='\n'||ch==='\r')){rows.at(-1).push(field);field='';if(ch!=='\t'){if(ch==='\r'&&text[i+1]==='\n')i++;rows.push([]);}}else field+=ch;}if(quoted)err('InvalidTSV');rows.at(-1).push(field);if(rows.length>1&&rows.at(-1).length===1&&rows.at(-1)[0]==='')rows.pop();return rows;}
