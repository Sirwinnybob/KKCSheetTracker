import { test } from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';

const assets = new URL('../../main/assets/viewer/js/', import.meta.url);
// Resolve the viewer's offline import map when exercising its real Three.js classes in Node.
registerHooks({ resolve(specifier, context, next) {
    if (specifier === 'three') return { url: new URL('three.module.js', assets).href, shortCircuit: true };
    if (specifier.startsWith('three/addons/')) return { url: new URL(specifier.replace('three/addons/', 'three-addons/'), assets).href, shortCircuit: true };
    return next(specifier, context);
} });
const THREE = await import(new URL('three.module.js', assets));
const { MeshEdges } = await import(new URL('meshEdges.js', assets));
const { CoreEngine, cameraMatrixChanged, scaledPixelRatio } = await import(new URL('engine.js', assets));
const { readRenderSettings, setupRenderSettings } = await import(new URL('renderSettings.js', assets));

test('portal outlines are 1.5px, omit triangle seams, follow meshes and never intercept picks', () => {
    const scene = new THREE.Scene();
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial());
    mesh.position.set(2, 3, 4); mesh.scale.setScalar(2); scene.add(mesh);
    const manager = new MeshEdges(scene);
    manager.setEnabled(true);
    const line = mesh.children[0];
    assert.equal(line.material.linewidth, 1.5);
    assert.equal(line.geometry.attributes.instanceStart.count, 12);
    scene.updateMatrixWorld(true);
    assert.ok(line.matrixWorld.equals(mesh.matrixWorld));
    assert.equal(line.material.depthTest, true);
    assert.equal(line.material.depthWrite, false);
    const ray = new THREE.Raycaster(new THREE.Vector3(2, 3, 10), new THREE.Vector3(0, 0, -1));
    const hits = ray.intersectObjects(scene.children, true);
    assert.ok(hits.length > 0 && hits.every(hit => hit.object === mesh));
    manager.setEnabled(false); manager.setEnabled(true);
    assert.equal(mesh.children.length, 1);
    let disposed = false;
    line.geometry.addEventListener('dispose', () => { disposed = true; });
    manager.remove(mesh);
    assert.equal(disposed, true); assert.equal(mesh.children.length, 0);
    manager.dispose();
});

test('quality configures real offscreen sample counts and wakes the on-demand renderer', () => {
    for (const webgl2 of [false, true]) {
        let requests = 0;
        const engine = { renderer: { capabilities: { isWebGL2: webgl2, maxSamples: 4 } },
            composer: { renderTarget1: new THREE.WebGLRenderTarget(), renderTarget2: new THREE.WebGLRenderTarget() },
            fxaaPass: {}, requestRender() { requests++; },
            _applyAntialiasing: CoreEngine.prototype._applyAntialiasing
        };
        for (const [quality, samples, fxaa] of [['off', 0, false], ['low', 0, true], ['medium', 2, true], ['high', 4, true]]) {
            CoreEngine.prototype.setAntialiasingQuality.call(engine, quality);
            assert.equal(engine.composer.renderTarget1.samples, webgl2 ? samples : 0);
            assert.equal(engine.composer.renderTarget2.samples, webgl2 ? samples : 0);
            assert.equal(engine.fxaaPass.enabled, fxaa);
        }
        assert.equal(requests, 4);
    }
});

test('native preferences survive changing viewer origins and invalid settings use defaults', () => {
    assert.deepEqual(readRenderSettings(null, null), { showOutlines: true, antialiasingQuality: 'high' });
    assert.deepEqual(readRenderSettings(null, { getRenderSettings: () => '{"showOutlines":false,"antialiasingQuality":"low"}' }),
        { showOutlines: false, antialiasingQuality: 'low' });
    assert.deepEqual(readRenderSettings({ getItem: () => '{"antialiasingQuality":"invalid"}' }, null),
        { showOutlines: true, antialiasingQuality: 'high' });
});

test('settings controls persist changes, wake idle rendering, and detach on disposal', () => {
    const toggle = new EventTarget(), quality = new EventTarget(), panel = {};
    const controls = { 'mesh-edges-toggle': toggle, 'antialiasing-quality': quality, 'viewer-render-settings': panel };
    const writes = [], native = [];
    let requests = 0, enabled;
    const engine = { setAntialiasingQuality(value) { this.antialiasingQuality = value; }, requestRender() { requests++; } };
    const settings = setupRenderSettings(engine, { setEnabled(value) { enabled = value; } }, {
        elements: { getElementById: id => controls[id] }, storage: { getItem: () => null, setItem: (key, value) => writes.push(JSON.parse(value)) },
        bridge: { setRenderSettings: (...args) => native.push(args) }
    });
    assert.equal(toggle.checked, true); assert.equal(quality.value, 'high'); assert.equal(panel.hidden, false);
    toggle.checked = false; toggle.dispatchEvent(new Event('change'));
    quality.value = 'low'; quality.dispatchEvent(new Event('change'));
    assert.equal(enabled, false); assert.equal(requests, 2);
    assert.deepEqual(writes.at(-1), { showOutlines: false, antialiasingQuality: 'low' });
    assert.deepEqual(native.at(-1), [false, 'low']);
    settings.dispose(); toggle.dispatchEvent(new Event('change'));
    assert.equal(requests, 2);
});

test('deferred outlines stay hidden while the preference remains enabled', () => {
    const scene = new THREE.Scene();
    const manager = new MeshEdges(scene);
    manager.setEnabled(true);
    manager.setRevealed(false);
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial());
    scene.add(mesh);
    manager.add(scene);
    const line = mesh.children[0];
    assert.equal(line.visible, false);
    manager.setRevealed(true);
    assert.equal(line.visible, true);
    manager.setEnabled(false);
    assert.equal(line.visible, false);
    manager.setRevealed(false);
    manager.setEnabled(true);
    assert.equal(line.visible, false);
    manager.setRevealed(true);
    assert.equal(line.visible, true);
    manager.dispose();
});

test('camera motion detection covers direct joystick moves and settled damping tails', () => {
    const identity = new THREE.Matrix4().elements;
    assert.equal(cameraMatrixChanged(null, identity), true);
    assert.equal(cameraMatrixChanged(identity, [...identity]), false);
    const moved = [...identity];
    moved[12] = 0.01;
    assert.equal(cameraMatrixChanged(identity, moved), true);
    const tiny = [...identity];
    tiny[12] = 1e-8;
    assert.equal(cameraMatrixChanged(identity, tiny), false);
});

test('anti-aliasing is reserved for the idle path and keeps the chosen quality', () => {
    for (const webgl2 of [false, true]) {
        let requests = 0;
        const engine = {
            renderer: { capabilities: { isWebGL2: webgl2, maxSamples: 4 } },
            composer: { renderTarget1: new THREE.WebGLRenderTarget(), renderTarget2: new THREE.WebGLRenderTarget() },
            fxaaPass: {},
            antialiasingQuality: 'high',
            antialiasingActive: true,
            requestRender() { requests++; },
            _applyAntialiasing: CoreEngine.prototype._applyAntialiasing
        };
        CoreEngine.prototype.setAntialiasingQuality.call(engine, 'high');
        assert.equal(engine.composer.renderTarget1.samples, webgl2 ? 4 : 0);
        assert.equal(engine.fxaaPass.enabled, true);

        CoreEngine.prototype.setAntialiasingActive.call(engine, false);
        assert.equal(engine.composer.renderTarget1.samples, 0);
        assert.equal(engine.composer.renderTarget2.samples, 0);
        assert.equal(engine.fxaaPass.enabled, false);

        CoreEngine.prototype.setAntialiasingQuality.call(engine, 'medium');
        assert.equal(engine.composer.renderTarget1.samples, 0);
        assert.equal(engine.fxaaPass.enabled, false);

        CoreEngine.prototype.setAntialiasingActive.call(engine, true);
        assert.equal(engine.composer.renderTarget1.samples, webgl2 ? 2 : 0);
        assert.equal(engine.fxaaPass.enabled, true);

        CoreEngine.prototype.setAntialiasingActive.call(engine, false);
        CoreEngine.prototype.setAntialiasingQuality.call(engine, 'off');
        CoreEngine.prototype.setAntialiasingActive.call(engine, true);
        assert.equal(engine.fxaaPass.enabled, false);
        assert.equal(engine.composer.renderTarget1.samples, 0);
        assert.equal(requests, 7);
    }
});

test('render scale follows the device resolution instead of a fixed pixel size', () => {
    assert.equal(scaledPixelRatio(2, 0.5), 1);
    assert.equal(scaledPixelRatio(1.5, 0.5), 0.75);
    assert.equal(scaledPixelRatio(3, 0.5), 1);
    assert.equal(scaledPixelRatio(2, 1), 2);
    assert.equal(scaledPixelRatio(2, 0.25), 0.5);
    assert.equal(scaledPixelRatio(undefined, undefined), 0.75);
    assert.equal(scaledPixelRatio(2, 0), 0.2);
});
