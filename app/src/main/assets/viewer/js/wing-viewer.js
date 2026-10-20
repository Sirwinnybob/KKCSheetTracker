import * as THREE from 'three';
import { CoreEngine } from './engine.js';
import { LineSegments2 } from 'three/addons/lines/LineSegments2.js';
import { LineSegmentsGeometry } from 'three/addons/lines/LineSegmentsGeometry.js';
import { LineMaterial } from 'three/addons/lines/LineMaterial.js';
import { frameModel, setupViewerControls } from './viewer-controls.js';
import { wingControlAction } from './wing-interactions.js';
import { addWingEdgeOverlay, applyWingTransform, doorFacePrimitives, fallbackWingMaterialColor, renderableWingParts } from './wing-scene.js';

const status = document.getElementById('status');
const statusText = document.getElementById('status-text');
document.getElementById('mini-ui').remove();
const lightMode = new URLSearchParams(location.search).get('dark') === '0';
document.body.classList.toggle('light-mode', lightMode);
status.classList.add('visible');

const requestedMsaa = Number.parseInt(new URLSearchParams(location.search).get('msaa'), 10);
const msaaSamples = Number.isFinite(requestedMsaa) ? Math.min(Math.max(requestedMsaa, 0), 4) : 4;

let interaction;
function report(method, value) {
  try { window.KKCViewerBridge?.[method](value); } catch (_) { /* Standalone browser. */ }
}
const engine = new CoreEngine({
  isLightMode: lightMode,
  msaaSamples: msaaSamples,
  onBeforeRender: () => interaction?.step() === true,
  onViewerActiveChanged: active => report('setViewerActive', active),
  onInteractionStateChanged: active => report('setViewerInteracting', active)
});
const { scene } = engine;
const group = new THREE.Group(); scene.add(group);
let capInspection = false;
interaction = setupViewerControls(engine, () => [group]);
function fit() { frameModel(engine, group); }
function updateVisibility() {
  if (document.hidden) engine.suspendRendering();
  else engine.resumeRendering();
}
document.addEventListener('visibilitychange', updateVisibility);
window.addEventListener('pagehide', () => engine.suspendRendering());
window.addEventListener('pageshow', updateVisibility);
window.addEventListener('beforeunload', () => {
  interaction.dispose(); engine.suspendRendering();
});
updateVisibility();

function matrix(values) {
  const result = new THREE.Matrix4();
  const v = values || [];
  result.set(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15]);
  return result;
}
function addPart(part) {
  const primitives = doorFacePrimitives(part);
  const solids = primitives.length ? primitives : [{
    x: 0, y: 0, width: part.size.width, height: part.size.height, kind: 'solid', depthFactor: 1
  }];
  solids.forEach(solid => addSolid(part, solid));
}

function addSolid(part, solid) {
  const depth = part.size.depth * solid.depthFactor;
  const geometry = new THREE.BoxGeometry(solid.width, solid.height, depth);
  geometry.translate(solid.x + solid.width / 2, solid.y + solid.height / 2, depth / 2);
  const material = new THREE.MeshStandardMaterial({ color: fallbackWingMaterialColor(part), roughness: .7, metalness: 0 });
  if (part.texture) {
    new THREE.TextureLoader().load(part.texture.url, texture => {
      texture.colorSpace = THREE.SRGBColorSpace;
      if (part.texture.tile && part.texture.worldWidthInches && part.texture.worldHeightInches) {
        texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
        texture.repeat.set(
          Math.max(solid.width / part.texture.worldWidthInches, 0.01),
          Math.max(solid.height / part.texture.worldHeightInches, 0.01)
        );
      }
      material.color.set('#ffffff'); material.map = texture; material.needsUpdate = true;
      engine.requestRender();
    });
  }
  const mesh = new THREE.Mesh(geometry, material);
  applyWingTransform(mesh, part.transform.values, matrix);
  addWingEdgeOverlay(mesh, THREE, { LineSegments2, LineSegmentsGeometry, LineMaterial });
  mesh.userData = { ...part, doorFaceKind: solid.kind }; group.add(mesh);
}

function setCapInspection(enabled) {
  capInspection = enabled;
  group.children.forEach(mesh => {
    if (mesh.userData?.code !== 'FILLER') return;
    mesh.material.transparent = enabled;
    mesh.material.opacity = enabled ? .24 : 1;
    mesh.material.depthWrite = !enabled;
    mesh.material.needsUpdate = true;
  });
  engine.requestRender();
}

fetch('/api/wing').then(response => response.ok ? response.json() : Promise.reject(new Error(`HTTP ${response.status}`))).then(payload => {
  renderableWingParts(payload).forEach(addPart);
  fit(); status.remove();
}).catch(error => {
  console.error('Wing payload failed to load', error);
  status.classList.add('visible', 'error');
  statusText.textContent = 'Wing data is unavailable. Go back and reopen the viewer.';
});

window.wingViewer = {
  fit,
  reset: fit,
  toggleCapInspection: () => setCapInspection(!capInspection)
};

const wingControls = document.getElementById('wing-controls');
wingControls?.removeAttribute('hidden');
wingControls?.querySelectorAll('.wing-control').forEach(button => {
  button.addEventListener('click', () => {
    const action = wingControlAction(button.id);
    if (action === 'toggleCapInspection') {
      window.wingViewer.toggleCapInspection();
      button.setAttribute('aria-pressed', String(capInspection));
      button.textContent = capInspection ? 'Caps on' : 'Caps';
    } else if (action) {
      window.wingViewer[action]();
    }
  });
});

engine.start();
