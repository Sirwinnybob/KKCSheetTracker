// Run: node --test app/src/test/js
// Regression: the 3D viewer used to re-arm requestAnimationFrame unconditionally, so an idle,
// visible pane ran a full EffectComposer render at display rate (~90% of the WebView GPU thread).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RenderLoop } from '../../main/assets/viewer/js/renderLoop.js';

function harness(stepImpl) {
    const pending = new Map();
    let nextId = 1;
    let steps = 0;
    const loop = new RenderLoop({
        step: (t) => { steps++; return stepImpl(t); },
        raf: (cb) => { const id = nextId++; pending.set(id, cb); return id; },
        cancelRaf: (id) => pending.delete(id)
    });
    const frame = () => {
        const cbs = [...pending.values()];
        pending.clear();
        cbs.forEach((cb) => cb(0));
    };
    return { loop, frame, pending, steps: () => steps };
}

test('idle loop stops re-arming after a frame that reports no more work', () => {
    const h = harness(() => false);
    h.loop.start();
    h.frame();
    assert.equal(h.steps(), 1);
    assert.equal(h.pending.size, 0, 'no rAF may be pending when idle');
    h.frame();
    h.frame();
    assert.equal(h.steps(), 1, 'idle frames must not render');
});

test('loop keeps running while step reports work, then stops', () => {
    let remaining = 3;
    const h = harness(() => --remaining > 0);
    h.loop.start();
    for (let i = 0; i < 10; i++) h.frame();
    assert.equal(h.steps(), 3);
    assert.equal(h.pending.size, 0);
});

test('request() wakes an idle loop and coalesces duplicate requests', () => {
    const h = harness(() => false);
    h.loop.start();
    h.frame();
    h.loop.request();
    h.loop.request();
    assert.equal(h.pending.size, 1);
    h.frame();
    assert.equal(h.steps(), 2);
});

test('suspended loop cancels the pending frame and ignores requests', () => {
    const h = harness(() => true);
    h.loop.start();
    h.loop.suspend();
    assert.equal(h.pending.size, 0);
    h.loop.request();
    assert.equal(h.pending.size, 0);
    assert.equal(h.steps(), 0);
});

test('resume renders once and then goes idle if there is no work', () => {
    const h = harness(() => false);
    h.loop.start();
    h.loop.suspend();
    h.loop.resume();
    h.frame();
    h.frame();
    assert.equal(h.steps(), 1);
});

test('request() before start() does nothing', () => {
    const h = harness(() => false);
    h.loop.request();
    assert.equal(h.pending.size, 0);
});
