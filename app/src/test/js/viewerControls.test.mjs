import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from '../../main/assets/viewer/js/three.module.js';

import * as viewer from '../../main/assets/viewer/js/viewer-controls.js';

function fixture(aspect = 1) {
  const canvas = new EventTarget();
  canvas.getBoundingClientRect = () => ({ left: 120, top: 80, width: 600, height: 600 });
  const camera = new THREE.PerspectiveCamera(45, aspect, .01, 5000);
  camera.position.set(0, 0, 10);
  const controls = { target: new THREE.Vector3(), zoomSpeed: 1, update() {
    camera.lookAt(this.target); camera.updateMatrixWorld();
  } };
  controls.update();
  return { camera, controls, renderer: { domElement: canvas }, requestRender() {} };
}

function pointer(canvas, type, x, y, pointerId = 1) {
  const event = new Event(type);
  Object.assign(event, { clientX: x, clientY: y, pointerId, pointerType: 'touch', isPrimary: pointerId === 1 });
  canvas.dispatchEvent(event);
}

test('frame model keeps every transformed corner visible in portrait and landscape', () => {
  assert.equal(typeof viewer.frameModel, 'function');
  for (const aspect of [.4, 1.6]) {
    const engine = fixture(aspect);
    const model = new THREE.Group();
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(3.5, 35.25, 45.875));
    mesh.position.set(80, 25, -60);
    mesh.rotation.y = .8;
    model.add(mesh);
    viewer.frameModel(engine, model);
    assert.ok(engine.controls.target.distanceTo(new THREE.Vector3(80, 25, -60)) < 1e-8);
    const box = new THREE.Box3().setFromObject(model);
    for (const x of [box.min.x, box.max.x]) for (const y of [box.min.y, box.max.y]) for (const z of [box.min.z, box.max.z]) {
      const p = new THREE.Vector3(x, y, z).project(engine.camera);
      assert.ok(Math.abs(p.x) < .95 && Math.abs(p.y) < .95 && Math.abs(p.z) < 1, `clipped corner ${p.toArray()} at aspect ${aspect}`);
    }
  }
});

test('tap pivots to the touched surface using canvas bounds and ignores drags and pinches', () => {
  assert.equal(typeof viewer.setupViewerControls, 'function');
  const engine = fixture();
  const model = new THREE.Group();
  model.add(new THREE.Mesh(new THREE.BoxGeometry(8, 8, 1)));
  model.updateMatrixWorld(true);
  const keys = new EventTarget();
  const interaction = viewer.setupViewerControls(engine, () => [model], { getElementById: () => null }, keys);
  const canvas = engine.renderer.domElement;
  pointer(canvas, 'pointerdown', 480, 380);
  pointer(canvas, 'pointerup', 480, 380);
  assert.ok(Math.abs(engine.controls.target.x - .7870057685) < 1e-6);
  assert.ok(Math.abs(engine.controls.target.y) < 1e-8);
  assert.ok(Math.abs(engine.controls.target.z - .5) < 1e-8);
  const target = engine.controls.target.clone();
  pointer(canvas, 'pointerdown', 420, 380);
  pointer(canvas, 'pointermove', 460, 380);
  pointer(canvas, 'pointerup', 420, 380);
  assert.deepEqual(engine.controls.target, target, 'returning drag must not pivot');
  pointer(canvas, 'pointerdown', 420, 380);
  pointer(canvas, 'pointerdown', 440, 380, 2);
  pointer(canvas, 'pointerup', 440, 380, 2);
  pointer(canvas, 'pointerup', 420, 380);
  assert.deepEqual(engine.controls.target, target, 'pinch release must not pivot');
  interaction.dispose();
});

test('shared zoom responds to keyboard and stops on focus loss', () => {
  assert.equal(typeof viewer.setupViewerControls, 'function');
  const engine = fixture();
  const keys = new EventTarget();
  const interaction = viewer.setupViewerControls(engine, () => [], { getElementById: () => null }, keys);
  const event = new Event('keydown'); Object.assign(event, { key: '+' }); keys.dispatchEvent(event);
  assert.equal(interaction.step(), true);
  assert.ok(engine.camera.position.z < 10);
  keys.dispatchEvent(new Event('blur'));
  assert.equal(interaction.step(), false);
  interaction.dispose();
});
