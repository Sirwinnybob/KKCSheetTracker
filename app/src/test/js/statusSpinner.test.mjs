// Run: node --test app/src/test/js
// Regression: `.status-spin` ran `animation: statusSpin infinite` permanently, even while its
// `#status` pill was opacity:0. The running animation kept the WebView compositor ticking at the
// display refresh rate (about 105% process CPU with a 3D pane open and completely idle).
// The spinner may only animate while the status pill is actually visible.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const css = readFileSync(new URL('../../main/assets/viewer/css/viewer.css', import.meta.url), 'utf8');

function ruleBody(selector) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\#]/g, '\\$&');
    const match = css.match(new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`));
    return match ? match[1] : null;
}

test('spinner is paused by default so a hidden status pill costs no frames', () => {
    const body = ruleBody('.status-spin');
    assert.ok(body, '.status-spin rule must exist');
    assert.match(body, /animation:\s*statusSpin\b/);
    assert.match(body, /animation-play-state:\s*paused/);
});

test('spinner only runs while the status pill is visible', () => {
    const body = ruleBody('#status.visible .status-spin');
    assert.ok(body, '#status.visible .status-spin rule must exist');
    assert.match(body, /animation-play-state:\s*running/);
});
