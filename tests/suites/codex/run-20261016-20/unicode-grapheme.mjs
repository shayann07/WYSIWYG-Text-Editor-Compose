// Validation-only UAX #29 extended grapheme reference, Unicode 16.0.0 tables.
import fs from 'node:fs';import {createHash} from 'node:crypto';
const manifest=JSON.parse(fs.readFileSync(new URL('./unicode/sources.json',import.meta.url)));
function lines(name){const bytes=fs.readFileSync(new URL('./unicode/'+name,import.meta.url));if(createHash('sha256').update(bytes).digest('hex')!==manifest[name].sha256)throw Error('UnicodeDataHashMismatch');return bytes.toString('utf8').split(/\r?\n/).map(s=>s.split('#')[0].trim()).filter(Boolean);}
function ranges(name,select){return lines(name).flatMap(s=>{const parts=s.split(';').map(v=>v.trim()),v=select(parts);if(!v)return [];const pair=parts[0].split('..').map(x=>parseInt(x,16));return [[pair[0],pair[1]??pair[0],v]];}).sort((a,b)=>a[0]-b[0]);}
const gcb=ranges('GraphemeBreakProperty.txt',p=>p[1]),ep=ranges('emoji-data.txt',p=>p[1]==='Extended_Pictographic'?'EP':null),incb=ranges('DerivedCoreProperties.txt',p=>p[1]==='InCB'?p[2]:null);
function find(table,cp,fallback){let lo=0,hi=table.length-1;while(lo<=hi){const mid=(lo+hi)>>1,r=table[mid];if(cp<r[0])hi=mid-1;else if(cp>r[1])lo=mid+1;else return r[2];}return fallback;}
export function segmentText(text,granularity='grapheme'){
 if(granularity!=='grapheme')return [...new Intl.Segmenter('und',{granularity}).segment(text)]; // Word segmentation remains a qualified adapter/Unicode task.
 const a=[];let offset=0;for(const ch of text){const cp=ch.codePointAt(0);a.push({cp,index:offset,width:ch.length,g:find(gcb,cp,'Other'),ep:find(ep,cp,false),i:find(incb,cp,'None')});offset+=ch.length;}
 const cuts=[0],control=g=>['CR','LF','Control'].includes(g);
 for(let k=1;k<a.length;k++){const l=a[k-1],r=a[k];let join=false;
  if(l.g==='CR'&&r.g==='LF')join=true;
  else if(control(l.g)||control(r.g))join=false;
  else if(l.g==='L'&&['L','V','LV','LVT'].includes(r.g))join=true;
  else if(['LV','V'].includes(l.g)&&['V','T'].includes(r.g))join=true;
  else if(['LVT','T'].includes(l.g)&&r.g==='T')join=true;
  else if(['Extend','ZWJ','SpacingMark'].includes(r.g)||l.g==='Prepend')join=true;
  else if(r.i==='Consonant'){let j=k-1,linker=false;while(j>=0&&['Extend','Linker'].includes(a[j].i)){linker||=a[j].i==='Linker';j--;}join=linker&&j>=0&&a[j].i==='Consonant';}
  if(!join&&!control(l.g)&&!control(r.g)&&r.ep&&l.g==='ZWJ'){let j=k-2;while(j>=0&&a[j].g==='Extend')j--;join=j>=0&&!!a[j].ep;}
  if(!join&&l.g==='Regional_Indicator'&&r.g==='Regional_Indicator'){let n=0;for(let j=k-1;j>=0&&a[j].g==='Regional_Indicator';j--)n++;join=n%2===1;}
  if(!join)cuts.push(r.index);
 }
 if(cuts.at(-1)!==text.length)cuts.push(text.length);return cuts.slice(0,-1).map((index,k)=>({index,segment:text.slice(index,cuts[k+1])}));
}
