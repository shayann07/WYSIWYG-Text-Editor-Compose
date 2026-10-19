// Coordinate contract only, not AWT hosting/font/layout qualification.
// selectionLines are actual layout highlight fragments in reading-line order.
export function textLocation({markedLength=null,offset=null,composedCaret,selectionLines=[],collapsedCaret}){
  const caret=point=>point.orientation==='vertical'
    ?{x:point.x,y:point.y,width:point.lineExtent,height:0}
    :{x:point.x,y:point.y,width:0,height:point.lineExtent};
  if(markedLength!==null){
    if(!offset||!Number.isInteger(offset.charIndex))throw Error('InvalidComposedHit');
    const insertion=offset.charIndex+(offset.trailing?1:0);
    if(insertion<0||insertion>markedLength)throw Error('InvalidComposedHit');
    return caret(composedCaret(offset)); // Preserve leading/trailing hit affinity.
  }
  // The offset argument MUST NOT be inspected in this branch.
  if(!selectionLines.length)return caret(collapsedCaret);
  const last=Math.max(...selectionLines.map(l=>l.lineOrder));
  const fragments=selectionLines.filter(l=>l.lineOrder===last),line=fragments[0];
  if(line.orientation==='vertical')return {x:line.x,y:Math.min(...fragments.map(l=>l.y)),width:line.width,height:0};
  const x=line.direction==='rtl'?Math.max(...fragments.map(l=>l.x+l.width)):Math.min(...fragments.map(l=>l.x));
  return {x,y:line.y,width:0,height:line.height};
}
