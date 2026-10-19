// Canonical fixture codec adapted from Gemini; validation only.
import {validateDocument} from './document-tree.mjs';
export function encodeUtf16LEBase64(str) {
  const buf = Buffer.alloc(str.length * 2);
  for (let i = 0; i < str.length; i++) {
    buf.writeUInt16LE(str.charCodeAt(i), i * 2);
  }
  return {unitCount: str.length, utf16leBase64: buf.toString('base64')};
}

export function decodeUtf16LEBase64({unitCount, utf16leBase64}) {
  if (!Number.isSafeInteger(unitCount) || unitCount < 0 || typeof utf16leBase64 !== 'string') {
    throw Error('InvalidCanonicalTextChunk');
  }
  if(!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(utf16leBase64))throw Error('InvalidBase64');
  const buf = Buffer.from(utf16leBase64, 'base64');
  if(buf.toString('base64')!==utf16leBase64)throw Error('InvalidBase64');
  if (buf.length !== unitCount * 2) throw Error('Utf16LengthMismatch');
  const chars = new Array(unitCount);
  for (let i = 0; i < unitCount; i++) {
    chars[i] = String.fromCharCode(buf.readUInt16LE(i * 2));
  }
  return chars.join('');
}

export function serializeCanonicalDocument1(doc) {
  validateDocument(doc);
  const encodeNode = n => {
    const out = {id: n.id, type: n.type, props: structuredClone(n.props ?? {})};
    if (n.emptyBlockAttributes) out.emptyBlockAttributes = structuredClone(n.emptyBlockAttributes);
    if (n.units) {
      out.textChunk = encodeUtf16LEBase64(n.units.map(u => u.char).join(''));
      out.unitMeta = n.units.map(u => ({
        kind: u.kind ?? 'text',
        tokenId: u.tokenId,
        ...(u.nodeId ? {nodeId: u.nodeId} : {}),
        ...(u.locked ? {locked: true} : {}),
        ...(u.props ? {props: structuredClone(u.props)} : {}),
        attrs: structuredClone(u.attrs ?? {})
      }));
    }
    if (n.children) out.children = n.children.map(encodeNode);
    if (n.type === 'object' && n.props?.kind === 'table') {
      out.props.rows = n.props.rows.map(r => ({
        id: r.id,
        cells: r.cells.map(cell => ({
          id: cell.id,
          props: structuredClone(cell.props ?? {}),
          flow: encodeNode(cell.flow)
        }))
      }));
    }
    return out;
  };
  const payload = {
    format: 'editor-document/1',
    endianness: 'LE',
    id: doc.id,
    resources: structuredClone(doc.props?.resources ?? {}),
    annotations: structuredClone(doc.props?.annotations ?? []),
    body: encodeNode(doc.children[0])
  };
  return JSON.stringify(payload);
}

export function deserializeCanonicalDocument1(jsonStr) {
  const data = JSON.parse(jsonStr);
  if (data.format !== 'editor-document/1' || data.endianness !== 'LE') {
    throw Error('UnsupportedCanonicalFormat');
  }
  const decodeNode = n => {
    const out = {id: n.id, type: n.type, props: structuredClone(n.props ?? {})};
    if (n.emptyBlockAttributes) out.emptyBlockAttributes = structuredClone(n.emptyBlockAttributes);
    if (n.textChunk) {
      const str = decodeUtf16LEBase64(n.textChunk);
      if (!Array.isArray(n.unitMeta) || n.unitMeta.length !== str.length) {
        throw Error('CanonicalUnitMetadataMismatch');
      }
      out.units = n.unitMeta.map((m, i) => ({
        kind: m.kind ?? 'text',
        tokenId: m.tokenId,
        ...(m.nodeId ? {nodeId: m.nodeId} : {}),
        ...(m.locked ? {locked: true} : {}),
        ...(m.props ? {props: structuredClone(m.props)} : {}),
        char: str[i],
        attrs: structuredClone(m.attrs ?? {}),
        originSessionId: null
      }));
    }
    if (n.children) out.children = n.children.map(decodeNode);
    if (n.type === 'object' && n.props?.kind === 'table') {
      out.props.rows = n.props.rows.map(r => ({
        id: r.id,
        cells: r.cells.map(cell => ({
          id: cell.id,
          ...(cell.props && Object.keys(cell.props).length ? {props: structuredClone(cell.props)} : {}),
          flow: decodeNode(cell.flow)
        }))
      }));
    }
    return out;
  };
  const doc = {
    id: data.id,
    type: 'document',
    props: {
      resources: structuredClone(data.resources ?? {}),
      annotations: structuredClone(data.annotations ?? [])
    },
    children: [decodeNode(data.body)]
  };
  validateDocument(doc);
  return doc;
}

