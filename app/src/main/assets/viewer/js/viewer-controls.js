import * as THREE from './three.module.js';
import { isTapGesture, zoomVelocityForJoystick } from './wing-interactions.js';

/** Fit rendered world bounds, including the horizontal field of view on a portrait tablet. */
export function frameModel(engine, model) {
    const box = new THREE.Box3().setFromObject(model);
    if (box.isEmpty()) return;
    const { camera, controls } = engine;
    const center = box.getCenter(new THREE.Vector3());
    const radius = Math.max(box.getSize(new THREE.Vector3()).length() / 2, .01);
    const vertical = THREE.MathUtils.degToRad(camera.getEffectiveFOV()) / 2;
    const horizontal = Math.atan(Math.tan(vertical) * camera.aspect);
    const distance = radius / Math.sin(Math.min(vertical, horizontal)) * 1.12;
    // Clear any damping left over from the previous view before setting an exact home position.
    const damping = controls.enableDamping;
    controls.enableDamping = false;
    controls.update();
    controls.target.copy(center);
    camera.position.copy(center).addScaledVector(new THREE.Vector3(1, 1, 1).normalize(), distance);
    camera.near = Math.max(radius / 1000, .001);
    camera.far = Math.max(5000, distance + radius * 4);
    camera.updateProjectionMatrix();
    controls.update();
    controls.enableDamping = damping;
    engine.requestRender();
}

/** One interaction implementation for both the GLB and manufactured wing viewers. */
export function setupViewerControls(engine, getObjects, elements = document, keys = window) {
    const { camera, controls, renderer } = engine;
    const canvas = renderer.domElement;
    const listeners = [];
    const on = (element, type, handler) => {
        if (!element) return;
        element.addEventListener(type, handler);
        listeners.push(() => element.removeEventListener(type, handler));
    };
    let down = null;
    let moved = false;
    const pointers = new Set();
    on(canvas, 'pointerdown', event => {
        pointers.add(event.pointerId);
        if (pointers.size > 1) { moved = true; return; }
        down = { x: event.clientX, y: event.clientY, id: event.pointerId };
        moved = false;
    });
    on(canvas, 'pointermove', event => {
        if (down && event.pointerId === down.id && !isTapGesture(down, { x: event.clientX, y: event.clientY })) moved = true;
    });
    on(canvas, 'pointercancel', event => { pointers.delete(event.pointerId); down = null; moved = true; });
    on(canvas, 'pointerup', event => {
        pointers.delete(event.pointerId);
        if (!down || event.pointerId !== down.id) return;
        const tap = !moved && isTapGesture(down, { x: event.clientX, y: event.clientY });
        down = null;
        if (!tap) return;
        const rect = canvas.getBoundingClientRect();
        if (!rect.width || !rect.height) return;
        const mouse = new THREE.Vector2(
            ((event.clientX - rect.left) / rect.width) * 2 - 1,
            -((event.clientY - rect.top) / rect.height) * 2 + 1
        );
        camera.updateMatrixWorld();
        const objects = getObjects();
        objects.forEach(object => object.updateWorldMatrix(true, true));
        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(mouse, camera);
        // Outlines are visual decoration; only a solid surface can become the orbit pivot.
        const hit = raycaster.intersectObjects(objects, true).find(hit => hit.object.isMesh);
        if (hit) {
            controls.target.copy(hit.point);
            controls.update();
            engine.requestRender();
        }
    });

    const handle = elements.getElementById('joystick-handle');
    const track = elements.getElementById('joystick-container');
    const direction = new THREE.Vector3();
    let dragging = false;
    let velocity = 0;
    const setPosition = y => {
        if (!handle || !track) return;
        handle.style.top = `${y - 18}px`;
        handle.setAttribute('aria-valuenow', String(Math.round((1 - y / (track.offsetHeight || 160)) * 100)));
    };
    const stop = () => {
        dragging = false;
        velocity = 0;
        setPosition((track?.offsetHeight || 160) / 2);
        engine.requestRender();
    };
    const move = clientY => {
        if (!dragging || !track) return;
        const rect = track.getBoundingClientRect();
        const y = THREE.MathUtils.clamp(clientY - rect.top, 0, rect.height);
        velocity = zoomVelocityForJoystick(y, rect.height);
        setPosition(y);
        engine.requestRender();
    };
    on(handle, 'pointerdown', event => {
        event.preventDefault(); event.stopPropagation();
        dragging = true;
        handle.setPointerCapture(event.pointerId);
        move(event.clientY);
    });
    on(handle, 'pointermove', event => move(event.clientY));
    on(handle, 'pointerup', event => {
        stop();
        if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
    });
    on(handle, 'pointercancel', stop);
    on(handle, 'lostpointercapture', stop);
    const zoomKeys = ['+', '=', '-', '_', 'PageUp', 'PageDown'];
    const keydown = event => {
        if (event.ctrlKey || event.altKey || event.metaKey) return;
        if (['INPUT', 'TEXTAREA', 'SELECT'].includes(event.target?.tagName) || event.target?.isContentEditable) return;
        const key = event.key;
        const v = ['+', '=', 'ArrowUp', 'ArrowRight'].includes(key) ? .5
            : ['-', '_', 'ArrowDown', 'ArrowLeft'].includes(key) ? -.5
            : key === 'PageUp' ? 1 : key === 'PageDown' ? -1 : 0;
        if (!v) return;
        event.preventDefault();
        velocity = Math.sign(v) * v * v * .15;
        setPosition((track?.offsetHeight || 160) * (1 - v) / 2);
        engine.requestRender();
    };
    on(handle, 'keydown', event => { event.stopPropagation(); keydown(event); });
    on(handle, 'keyup', stop);
    on(keys, 'keydown', event => { if (zoomKeys.includes(event.key)) keydown(event); });
    on(keys, 'keyup', event => { if (zoomKeys.includes(event.key)) stop(); });
    on(keys, 'blur', () => { stop(); down = null; pointers.clear(); });
    return {
        step() {
            if (!velocity) return false;
            if (velocity > 0 && camera.position.distanceTo(controls.target) < .5) return false;
            camera.getWorldDirection(direction);
            camera.position.addScaledVector(direction, velocity * controls.zoomSpeed);
            return true;
        },
        dispose() { stop(); listeners.forEach(remove => remove()); }
    };
}
