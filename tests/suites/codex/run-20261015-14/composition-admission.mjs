// Validation contract for a tagged flow projection; no Compose/platform code.
// Segment kinds: text/softLf (with blockId and blockOffset), blockSeparator,
// atom, cellBoundary. Coordinates are UTF-16 half-open flow offsets.
export function admitCompositionRange(segments, range, affinity='before', activeBlockId=null) {
  const length=segments.length ? segments.at(-1).end : 0;
  if(!Array.isArray(range)||range.length!==2||!range.every(Number.isInteger)||range[0]<0||range[1]<range[0]||range[1]>length)
    return {status:'Rejected',reason:'InvalidTargetRange'};
  let previous=0;
  for(const s of segments){
    if(s.start!==previous||s.end<s.start)return {status:'Rejected',reason:'InvalidProjection'};
    previous=s.end;
  }
  const [start,end]=range;
  const editable=s=>s.kind==='text'||s.kind==='softLf';
  if(start===end) {
    const candidates=segments.filter(s=>editable(s)&&s.start<=start&&start<=s.end);
    if(!candidates.length)return {status:'Rejected',reason:'ProtectedCompositionBoundary'};
    const s=affinity==='after'?candidates.at(-1):candidates[0];
    if(activeBlockId!==null&&s.blockId!==activeBlockId)return {status:'Rejected',reason:'CompositionBlockMismatch'};
    return {status:'Accepted',blockId:s.blockId,range:[s.blockOffset+start-s.start,s.blockOffset+start-s.start]};
  }
  const touched=segments.filter(s=>s.start<end&&s.end>start);
  if(touched.some(s=>s.kind==='blockSeparator')||new Set(touched.filter(editable).map(s=>s.blockId)).size>1)
    return {status:'Rejected',reason:'CrossBlockComposition'};
  if(!touched.length||touched.some(s=>!editable(s)))return {status:'Rejected',reason:'ProtectedCompositionBoundary'};
  const first=touched[0],last=touched.at(-1);
  if(activeBlockId!==null&&first.blockId!==activeBlockId)return {status:'Rejected',reason:'CompositionBlockMismatch'};
  return {status:'Accepted',blockId:first.blockId,range:[first.blockOffset+start-first.start,last.blockOffset+end-last.start]};
}
