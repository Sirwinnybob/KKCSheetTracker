import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { isTapGesture, wingControlAction, zoomVelocityForJoystick } from './wing-interactions.js';
import { addWingEdgeOverlay, applyWingTransform, boundsDimensions, doorFacePrimitives, fallbackWingMaterialColor, renderableWingParts } from './wing-scene.js';

const container = document.getElementById('canvas-container');
const status = document.getElementById('status');
const statusText = document.getElementById('status-text');
document.getElementById('mini-ui').remove();

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
container.append(renderer.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color('#f2f0ea');
const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 5000);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
scene.add(new THREE.HemisphereLight(0xffffff, 0x5d584f, 2.2));
const key = new THREE.DirectionalLight(0xffffff, 1.5); key.position.set(60, 90, 40); scene.add(key);
const group = new THREE.Group(); scene.add(group);
let bounds;
let capInspection = false;
let zoomVelocity = 0;
const zoomDirection = new THREE.Vector3();
const pointerDown = new THREE.Vector2();
const pointerCurrent = new THREE.Vector2();

function resize() {
  const width = container.clientWidth || innerWidth, height = container.clientHeight || innerHeight;
  renderer.setSize(width, height, false); camera.aspect = width / Math.max(height, 1); camera.updateProjectionMatrix();
}
addEventListener('resize', resize); resize();

renderer.domElement.addEventListener('pointerdown', event => {
  if (event.pointerType === 'touch' && !event.isPrimary) return;
  pointerDown.set(event.clientX, event.clientY);
});
renderer.domElement.addEventListener('pointerup', event => {
  if (event.pointerType === 'touch' && !event.isPrimary) return;
  pointerCurrent.set(event.clientX, event.clientY);
  if (!isTapGesture(pointerDown, pointerCurrent)) return;
  const rect = renderer.domElement.getBoundingClientRect();
  const mouse = new THREE.Vector2(
    ((event.clientX - rect.left) / rect.width) * 2 - 1,
    -((event.clientY - rect.top) / rect.height) * 2 + 1
  );
  const raycaster = new THREE.Raycaster();
  raycaster.setFromCamera(mouse, camera);
  const hit = raycaster.intersectObjects(group.children, false)[0];
  if (hit) {
    controls.target.copy(hit.point);
    controls.update();
  }
});

const joystickHandle = document.getElementById('joystick-handle');
const joystickContainer = document.getElementById('joystick-container');
let draggingJoystick = false;

function resetJoystick() {
  zoomVelocity = 0;
  if (joystickHandle && joystickContainer) {
    joystickHandle.style.top = `${joystickContainer.offsetHeight / 2 - 18}px`;
    joystickHandle.setAttribute('aria-valuenow', '50');
  }
}
function updateJoystick(clientY) {
  if (!draggingJoystick || !joystickContainer || !joystickHandle) return;
  const rect = joystickContainer.getBoundingClientRect();
  const relativeY = Math.max(0, Math.min(rect.height, clientY - rect.top));
  zoomVelocity = zoomVelocityForJoystick(relativeY, rect.height);
  joystickHandle.style.top = `${relativeY - 18}px`;
  joystickHandle.setAttribute('aria-valuenow', Math.round(((rect.height - relativeY) / rect.height) * 100).toString());
}
if (joystickHandle) {
  joystickHandle.onpointerdown = event => {
    event.preventDefault(); event.stopPropagation();
    draggingJoystick = true;
    joystickHandle.setPointerCapture(event.pointerId);
    updateJoystick(event.clientY);
  };
  joystickHandle.onpointermove = event => updateJoystick(event.clientY);
  joystickHandle.onpointerup = event => {
    draggingJoystick = false;
    resetJoystick();
    joystickHandle.releasePointerCapture(event.pointerId);
  };
  joystickHandle.addEventListener('keydown', event => {
    const direction = event.key === 'ArrowUp' || event.key === 'ArrowRight' || event.key === '+' || event.key === '=' ? 0.5
      : event.key === 'ArrowDown' || event.key === 'ArrowLeft' || event.key === '-' || event.key === '_' ? -0.5
      : event.key === 'PageUp' ? 1 : event.key === 'PageDown' ? -1 : 0;
    if (!direction) return;
    event.preventDefault();
    zoomVelocity = Math.sign(direction) * direction * direction * 0.15;
  });
  joystickHandle.addEventListener('keyup', resetJoystick);
}

function fit() {
  if (!bounds) return;
  const center = new THREE.Vector3((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2, (bounds.minZ + bounds.maxZ) / 2);
  const size = boundsDimensions(bounds);
  const radius = Math.max(size.width, size.height, size.depth) * 0.82;
  controls.target.copy(center); camera.position.copy(center).add(new THREE.Vector3(radius, radius * .7, radius));
  camera.near = Math.max(radius / 1000, .01); camera.far = radius * 100; camera.updateProjectionMatrix(); controls.update();
}

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
    });
  }
  const mesh = new THREE.Mesh(geometry, material);
  applyWingTransform(mesh, part.transform.values, matrix);
  addWingEdgeOverlay(mesh, THREE);
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
}

fetch('/api/wing').then(response => response.ok ? response.json() : Promise.reject(new Error(`HTTP ${response.status}`))).then(payload => {
  bounds = payload.bounds;
  renderableWingParts(payload).forEach(addPart);
  fit(); status.remove();
}).catch(error => {
  console.error('Wing payload failed to load', error);
  statusText.textContent = 'Wing data is unavailable.';
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

function frame() {
  if (zoomVelocity !== 0) {
    camera.getWorldDirection(zoomDirection);
    const distance = camera.position.distanceTo(controls.target);
    if (!(zoomVelocity > 0 && distance < 0.5)) camera.position.addScaledVector(zoomDirection, zoomVelocity * controls.zoomSpeed);
  }
  controls.update(); renderer.render(scene, camera); requestAnimationFrame(frame);
}
frame();
