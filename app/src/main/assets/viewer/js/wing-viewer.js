import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const container = document.getElementById('canvas-container');
const status = document.getElementById('status');
const statusText = document.getElementById('status-text');
document.getElementById('mini-ui').remove();
document.getElementById('joystick-container').remove();

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

function resize() {
  const width = container.clientWidth || innerWidth, height = container.clientHeight || innerHeight;
  renderer.setSize(width, height, false); camera.aspect = width / Math.max(height, 1); camera.updateProjectionMatrix();
}
addEventListener('resize', resize); resize();

function fit() {
  if (!bounds) return;
  const center = new THREE.Vector3((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2, (bounds.minZ + bounds.maxZ) / 2);
  const radius = Math.max(bounds.width, bounds.height, bounds.depth) * 0.82;
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
  const geometry = new THREE.BoxGeometry(part.size.width, part.size.height, part.size.depth);
  geometry.translate(part.size.width / 2, part.size.height / 2, part.size.depth / 2);
  const material = new THREE.MeshStandardMaterial({ color: part.doorId ? '#ddd5c6' : '#d6c0a0', roughness: .7, metalness: 0 });
  if (part.texture) {
    new THREE.TextureLoader().load(part.texture.url, texture => {
      texture.colorSpace = THREE.SRGBColorSpace;
      if (part.texture.tile && part.texture.worldWidthInches && part.texture.worldHeightInches) {
        texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
        texture.repeat.set(
          Math.max(part.size.width / part.texture.worldWidthInches, 0.01),
          Math.max(part.size.height / part.texture.worldHeightInches, 0.01)
        );
      }
      material.color.set('#ffffff'); material.map = texture; material.needsUpdate = true;
    });
  }
  const mesh = new THREE.Mesh(geometry, material);
  mesh.matrixAutoUpdate = false; mesh.matrix.copy(matrix(part.transform.values));
  mesh.userData = part; group.add(mesh);
}

fetch('/api/wing').then(response => response.ok ? response.json() : Promise.reject()).then(payload => {
  bounds = payload.bounds;
  payload.parts.forEach(addPart);
  fit(); status.remove();
}).catch(() => { statusText.textContent = 'Wing data is unavailable.'; });

function frame() { controls.update(); renderer.render(scene, camera); requestAnimationFrame(frame); }
frame();
