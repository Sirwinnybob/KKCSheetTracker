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

/**
 * Three.js needs this explicit invalidation when a manually supplied local
 * matrix is assigned after the parent group has already rendered once.
 */
export function applyWingTransform(mesh, values, createMatrix) {
  mesh.matrixAutoUpdate = false;
  mesh.matrix.copy(createMatrix(values));
  mesh.matrixWorldNeedsUpdate = true;
}

/** Gson serializes the six backing min/max values, not Kotlin's derived getters. */
export function boundsDimensions(bounds) {
  return {
    width: bounds.maxX - bounds.minX,
    height: bounds.maxY - bounds.minY,
    depth: bounds.maxZ - bounds.minZ
  };
}

/** Material cache entries without an image are intentional solid-white finishes (for example Paint Grade). */
export function fallbackWingMaterialColor() {
  return '#ffffff';
}

/** Attaches a screen-readable, depth-aware outline that inherits the part transform. */
export function addWingEdgeOverlay(mesh, three) {
  // Lines share the part's boundary. Move the filled polygon slightly away
  // in depth, then draw the line second so mobile GPUs do not alternate them.
  mesh.material.polygonOffset = true;
  mesh.material.polygonOffsetFactor = 1;
  mesh.material.polygonOffsetUnits = 1;
  const overlay = new three.LineSegments(
    new three.EdgesGeometry(mesh.geometry),
    new three.LineBasicMaterial({
      color: '#050505',
      depthTest: true,
      depthWrite: false,
      toneMapped: false
    })
  );
  overlay.name = 'part-edge-overlay';
  overlay.renderOrder = mesh.renderOrder + 1;
  mesh.add(overlay);
  return overlay;
}

/** Converts the publisher's finished-face partition into separate 3D solids. */
export function doorFacePrimitives(part) {
  const face = part?.doorFace;
  if (!face) return [];
  const valid = rect => rect && Number.isFinite(rect.x) && Number.isFinite(rect.y) &&
    Number.isFinite(rect.width) && Number.isFinite(rect.height) && rect.width > 0 && rect.height > 0;
  return [
    ...(Array.isArray(face.members) ? face.members : [])
      .filter(valid).map(rect => ({ ...rect, kind: 'member', depthFactor: 1 })),
    ...(Array.isArray(face.panels) ? face.panels : [])
      .filter(valid).map(rect => ({ ...rect, kind: 'panel', depthFactor: 0.72 }))
  ];
}
