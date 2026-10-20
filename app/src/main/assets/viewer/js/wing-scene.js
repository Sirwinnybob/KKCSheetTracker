const HIDDEN_CODES = new Set(['CAB', 'Case', 'Face2', 'Face4', 'DOR_OPEN', 'TS', 'DE']);

/** Pure payload guard shared by the browser bootstrap and Node regression tests. */
export function renderableWingParts(payload) {
  if (!payload || !Array.isArray(payload.parts)) return [];
  return payload.parts.filter(part =>
    part && !HIDDEN_CODES.has(part.code) && part.size &&
    Number.isFinite(part.size.width) && Number.isFinite(part.size.height) && Number.isFinite(part.size.depth) &&
    Array.isArray(part.transform?.values) && part.transform.values.length === 16
  );
}
