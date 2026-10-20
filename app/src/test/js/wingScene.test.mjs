import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { addWingEdgeOverlay, applyWingTransform, boundsDimensions, doorFacePrimitives, fallbackWingMaterialColor, renderableWingParts } from '../../main/assets/viewer/js/wing-scene.js';
import { isTapGesture, wingControlAction, zoomVelocityForJoystick } from '../../main/assets/viewer/js/wing-interactions.js';

test('wing scene accepts only complete manufactured solids', () => {
  const matrix = Array(16).fill(0);
  const parts = renderableWingParts({ parts: [
    { code: 'RF', size: { width: 1, height: 2, depth: .75 }, transform: { values: matrix } },
    { code: 'TS', size: { width: 1, height: 2, depth: .75 }, transform: { values: matrix } },
    { code: 'DOR', size: { width: 1, height: 2, depth: .75 }, transform: { values: [0] } }
  ] });
  assert.deepEqual(parts.map(part => part.code), ['RF']);
});

test('wing scene marks manually transformed parts for a world-matrix update', () => {
  const copied = [];
  const mesh = {
    matrix: { copy: value => copied.push(value) },
    matrixAutoUpdate: true,
    matrixWorldNeedsUpdate: false
  };
  const values = Array.from({ length: 16 }, (_, index) => index);

  applyWingTransform(mesh, values, value => ({ value }));

  assert.equal(mesh.matrixAutoUpdate, false);
  assert.deepEqual(copied, [{ value: values }]);
  assert.equal(mesh.matrixWorldNeedsUpdate, true);
});

test('wing scene derives camera dimensions from serialized min and max bounds', () => {
  assert.deepEqual(boundsDimensions({
    minX: 0, maxX: 3.5,
    minY: 0, maxY: 35.25,
    minZ: -14.5, maxZ: 31.375
  }), { width: 3.5, height: 35.25, depth: 45.875 });
});

test('wing scene honors the material catalog solid-white fallback when no bitmap is published', () => {
  assert.equal(fallbackWingMaterialColor({ texture: null }), '#ffffff');
});

test('wing scene renders each authored door face member and recesses the panel', () => {
  assert.deepEqual(doorFacePrimitives({
    doorFace: {
      members: [{ x: 0, y: 0, width: 2, height: 30 }],
      panels: [{ x: 2, y: 2, width: 16, height: 26 }]
    }
  }), [
    { x: 0, y: 0, width: 2, height: 30, kind: 'member', depthFactor: 1 },
    { x: 2, y: 2, width: 16, height: 26, kind: 'panel', depthFactor: 0.72 }
  ]);
});

test('wing scene places black depth-tested edge lines on each rendered solid', () => {
  const calls = [];
  const mesh = {
    geometry: { id: 'part-geometry' },
    material: {},
    renderOrder: 4,
    add: child => calls.push(child)
  };
  const three = {
    EdgesGeometry: class {
      constructor(geometry) { this.geometry = geometry; }
    },
    LineBasicMaterial: class {
      constructor(options) { this.options = options; }
    },
    LineSegments: class {
      constructor(geometry, material) { this.geometry = geometry; this.material = material; }
    }
  };

  const overlay = addWingEdgeOverlay(mesh, three);

  assert.equal(calls[0], overlay);
  assert.equal(overlay.name, 'part-edge-overlay');
  assert.equal(overlay.renderOrder, 5);
  assert.equal(overlay.geometry.geometry, mesh.geometry);
  assert.deepEqual(mesh.material, {
    polygonOffset: true,
    polygonOffsetFactor: 1,
    polygonOffsetUnits: 1
  });
  assert.deepEqual(overlay.material.options, {
    color: '#050505', depthTest: true, depthWrite: false, toneMapped: false
  });
});

test('wing controls pivot only for a deliberate tap', () => {
  assert.equal(isTapGesture({ x: 30, y: 20 }, { x: 36, y: 23 }), true);
  assert.equal(isTapGesture({ x: 30, y: 20 }, { x: 39, y: 20 }), false);
});

test('wing controls square joystick input and return to rest at center', () => {
  assert.equal(zoomVelocityForJoystick(80, 160), 0);
  assert.equal(zoomVelocityForJoystick(0, 160), 0.15);
  assert.equal(zoomVelocityForJoystick(160, 160), -0.15);
});

test('wing control buttons map to the established viewer operations', () => {
  assert.equal(wingControlAction('wing-frame-btn'), 'fit');
  assert.equal(wingControlAction('wing-reset-btn'), 'reset');
  assert.equal(wingControlAction('wing-caps-btn'), 'toggleCapInspection');
});

test('wing overlays follow the WebGL canvas so Android WebView composites them above it', () => {
  const html = readFileSync(new URL('../../main/assets/viewer/viewer.html', import.meta.url), 'utf8');
  const canvas = html.indexOf('id="canvas-container"');
  assert.ok(canvas >= 0);
  assert.ok(html.indexOf('id="joystick-container"') > canvas);
  assert.ok(html.indexOf('id="wing-controls"') > canvas);
});
