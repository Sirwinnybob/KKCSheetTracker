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

/** Edge line thickness in device pixels; native GL lines are fixed at 1px. */
export const WING_EDGE_LINE_WIDTH = 1.6;

/**
 * Attaches a screen-readable, depth-aware outline that inherits the part transform.
 * With `fatLines` the outline is drawn as screen-space quads so it can be thicker than 1px;
 * without it the outline falls back to native 1px lines.
 */
export function addWingEdgeOverlay(mesh, three, fatLines = null) {
  // Lines share the part's boundary. Move the filled polygon slightly away
  // in depth, then draw the line second so mobile GPUs do not alternate them.
  mesh.material.polygonOffset = true;
  mesh.material.polygonOffsetFactor = 1;
  mesh.material.polygonOffsetUnits = 1;
  const edges = new three.EdgesGeometry(mesh.geometry);
  const overlay = fatLines ? fatEdgeOverlay(edges, three, fatLines) : new three.LineSegments(
    edges,
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

function fatEdgeOverlay(edges, three, { LineSegments2, LineSegmentsGeometry, LineMaterial }) {
  const geometry = new LineSegmentsGeometry().fromEdgesGeometry(edges);
  edges.dispose?.();
  const material = new LineMaterial({
    color: 0x050505,
    linewidth: WING_EDGE_LINE_WIDTH,
    depthTest: true,
    depthWrite: false,
    toneMapped: false
  });
  const overlay = new LineSegments2(geometry, material);
  // LineSegments2 is a Mesh; keep it out of tap picking so the pivot lands on the part surface.
  overlay.raycast = () => {};
  // LineMaterial sizes lines against the target being drawn into (the composer's offscreen
  // target here, not the canvas), so refresh its resolution on every draw.
  overlay.onBeforeRender = renderer => {
    const target = renderer.getRenderTarget();
    if (target) material.resolution.set(target.width, target.height);
    else {
      const size = renderer.getDrawingBufferSize(new three.Vector2());
      material.resolution.set(size.x, size.y);
    }
  };
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
