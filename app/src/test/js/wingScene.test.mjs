import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderableWingParts } from '../../main/assets/viewer/js/wing-scene.js';

test('wing scene accepts only complete manufactured solids', () => {
  const matrix = Array(16).fill(0);
  const parts = renderableWingParts({ parts: [
    { code: 'RF', size: { width: 1, height: 2, depth: .75 }, transform: { values: matrix } },
    { code: 'TS', size: { width: 1, height: 2, depth: .75 }, transform: { values: matrix } },
    { code: 'DOR', size: { width: 1, height: 2, depth: .75 }, transform: { values: [0] } }
  ] });
  assert.deepEqual(parts.map(part => part.code), ['RF']);
});
