// Run: node --test app/src/test/js
// Outlines and anti-aliasing are deferred so tablet GPUs skip them while the user moves the camera.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { IdleReveal } from '../../main/assets/viewer/js/idleReveal.js';

function harness() {
    const timers = [];
    const renders = [];
    let applied = null;
    const reveal = new IdleReveal({
        idleDelayMs: 1000,
        schedule: (callback, delay) => {
            const timer = { callback, delay, cancelled: false };
            timers.push(timer);
            return timer;
        },
        cancel: timer => { timer.cancelled = true; },
        requestRender: () => renders.push(true)
    });
    reveal.register(visible => { applied = visible; });
    return { reveal, timers, renders, isVisible: () => applied };
}

test('outlines start hidden and stay hidden while the camera moves', () => {
    const h = harness();
    assert.equal(h.isVisible(), false);
    h.reveal.frame(true);
    h.reveal.frame(true);
    assert.equal(h.timers.length, 0);
    assert.equal(h.isVisible(), false);
});

test('a still camera reveals outlines only after the idle delay has repainted', () => {
    const h = harness();
    h.reveal.frame(false);
    assert.equal(h.timers.length, 1);
    assert.equal(h.timers[0].delay, 1000);
    assert.equal(h.isVisible(), false, 'the timer alone must not show outlines');

    h.timers[0].callback();
    assert.deepEqual(h.renders, [true]);
    assert.equal(h.isVisible(), false, 'no frame has drawn the outlines yet');

    h.reveal.frame(false);
    assert.equal(h.isVisible(), true);
    h.reveal.frame(false);
    assert.equal(h.timers.length, 1);
});

test('moving again hides revealed outlines immediately and cancels a pending reveal', () => {
    const h = harness();
    h.reveal.frame(false);
    h.reveal.frame(true);
    assert.equal(h.timers[0].cancelled, true);
    assert.equal(h.isVisible(), false);

    h.reveal.frame(false);
    h.timers[1].callback();
    h.reveal.frame(false);
    assert.equal(h.isVisible(), true);

    h.reveal.frame(true);
    assert.equal(h.isVisible(), false);
});

test('dispose cancels a pending reveal timer', () => {
    const h = harness();
    h.reveal.frame(false);
    h.reveal.dispose();
    assert.equal(h.timers[0].cancelled, true);
    h.reveal.frame(false);
    assert.equal(h.timers.length, 1);
});
