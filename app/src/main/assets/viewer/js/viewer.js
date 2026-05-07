import { CoreEngine } from './engine.js';
import { loadModel } from './modelLoader.js';
// Resolved via importmap in viewer.html
import * as THREE from 'three';

let scene, camera, renderer, controls, composer, engine;
let zoomVelocity = 0;
let loadedModel = null;

function disposeModel(model) {
    if (!model) return;
    model.traverse(obj => {
        if (!obj.isMesh) return;
        if (obj.geometry) obj.geometry.dispose();
        const mats = Array.isArray(obj.material) ? obj.material : [obj.material];
        mats.forEach(mat => { if (mat && typeof mat.dispose === 'function') mat.dispose(); });
    });
}

function frameLoadedModel(model) {
    const box = new THREE.Box3().setFromObject(model);
    const center = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3());
    const maxDim = Math.max(size.x, size.y, size.z);
    camera.position.set(center.x + maxDim, center.y + maxDim, center.z + maxDim);
    camera.lookAt(center);
    controls.target.copy(center);
    controls.update();
}

const statusEl   = document.getElementById('status');
const statusText = document.getElementById('status-text');
const updateStatus = (msg, state = null) => {
    if (statusEl) {
        if (statusText) statusText.innerText = msg;
        else statusEl.innerText = msg;
        statusEl.classList.toggle('error', state === 'error' || state === true);
        statusEl.classList.toggle('success', state === 'success');
        statusEl.classList.toggle('visible', msg.length > 0);
    }
};

// Android injects dark/light via URL param: ?dark=1 or ?dark=0
function isLightMode() {
    const urlParams = new URLSearchParams(window.location.search);
    const darkParam = urlParams.get('dark');
    if (darkParam !== null) return darkParam === '0';
    return false; // default dark
}

// Apply theme to body so CSS vars work
function applyTheme(lightMode) {
    document.body.classList.toggle('light-mode', lightMode);
}

async function init() {
    const urlParams   = new URLSearchParams(window.location.search);
    const jobCode     = urlParams.get('job');
    const initialRoom = urlParams.get('room');

    const lightMode = isLightMode();
    applyTheme(lightMode);

    // Wire zoom keyboard shortcuts
    window.addEventListener('keydown', (e) => {
        const zoomKeys = ['+', '=', '-', '_', 'PageUp', 'PageDown'];
        if (zoomKeys.includes(e.key)) {
            const joystick = document.getElementById('joystick-handle');
            if (joystick) joystick.dispatchEvent(new KeyboardEvent('keydown', { key: e.key, bubbles: false }));
        }
    });
    window.addEventListener('keyup', (e) => {
        const zoomKeys = ['+', '=', '-', '_', 'PageUp', 'PageDown'];
        if (zoomKeys.includes(e.key)) {
            const joystick = document.getElementById('joystick-handle');
            if (joystick) joystick.dispatchEvent(new KeyboardEvent('keyup', { key: e.key, bubbles: false }));
        }
    });

    if (!jobCode || !initialRoom) {
        updateStatus("Missing job or room parameter", true);
        return;
    }

    updateStatus("Initializing 3D...");

    try {
        // --- CORE ENGINE SETUP ---
        engine = new CoreEngine({
            containerId: 'canvas-container',
            isLightMode: lightMode,
            onBeforeRender: () => {
                if (zoomVelocity !== 0 && camera && controls) {
                    const direction = new THREE.Vector3();
                    camera.getWorldDirection(direction);
                    const dist = camera.position.distanceTo(controls.target);
                    if (!(zoomVelocity > 0 && dist < 0.5)) {
                        camera.position.addScaledVector(direction, zoomVelocity * controls.zoomSpeed);
                    }
                }
            }
        });

        scene    = engine.scene;
        camera   = engine.camera;
        renderer = engine.renderer;
        controls = engine.controls;
        composer = engine.composer;

        // --- SINGLE TAP → PIVOT (consistent with Assimp app) ---
        let tapPos = new THREE.Vector2();
        let pointerDownPos = new THREE.Vector2();
        let pointerHasMoved = false;
        const DRAG_THRESHOLD = 8;

        renderer.domElement.addEventListener('pointerdown', (e) => {
            if (e.pointerType === 'touch' && !e.isPrimary) return;
            pointerDownPos.set(e.clientX, e.clientY);
            pointerHasMoved = false;
        });

        renderer.domElement.addEventListener('pointermove', (e) => {
            if (e.pointerType === 'touch' && !e.isPrimary) return;
            if (pointerDownPos.distanceTo(new THREE.Vector2(e.clientX, e.clientY)) > DRAG_THRESHOLD) {
                pointerHasMoved = true;
            }
        });

        renderer.domElement.addEventListener('pointerup', (e) => {
            if (e.pointerType === 'touch' && !e.isPrimary) return;
            if (pointerHasMoved) return;

            const raycaster = new THREE.Raycaster();
            const mouse = new THREE.Vector2(
                (e.clientX / window.innerWidth)  *  2 - 1,
                -(e.clientY / window.innerHeight) * 2 + 1
            );
            raycaster.setFromCamera(mouse, camera);
            const intersects = raycaster.intersectObjects(scene.children, true);
            if (intersects.length > 0) {
                controls.target.copy(intersects[0].point);
                controls.update();
            }
        });

        // --- ZOOM JOYSTICK ---
        const joystickHandle    = document.getElementById('joystick-handle');
        const joystickContainer = document.getElementById('joystick-container');
        let isDraggingJoystick  = false;

        const updateJoystick = (clientY) => {
            if (!isDraggingJoystick || !joystickContainer) return;
            const rect    = joystickContainer.getBoundingClientRect();
            const relY    = Math.max(0, Math.min(rect.height, clientY - rect.top));
            const center  = rect.height / 2;
            const rawInput = (center - relY) / center;
            zoomVelocity  = Math.sign(rawInput) * (rawInput * rawInput) * 0.15;
            if (joystickHandle) {
                joystickHandle.style.top = `${relY - 18}px`;
                const percent = Math.round(((rect.height - relY) / rect.height) * 100);
                joystickHandle.setAttribute('aria-valuenow', percent.toString());
            }
        };

        if (joystickHandle) {
            joystickHandle.onpointerdown = (e) => {
                e.preventDefault();
                e.stopPropagation();
                isDraggingJoystick = true;
                updateJoystick(e.clientY);
                joystickHandle.setPointerCapture(e.pointerId);
            };
            joystickHandle.onpointermove = (e) => { if (isDraggingJoystick) updateJoystick(e.clientY); };
            joystickHandle.onpointerup = (e) => {
                isDraggingJoystick = false;
                zoomVelocity = 0;
                if (joystickHandle) {
                    joystickHandle.style.top = (joystickContainer.offsetHeight / 2 - 18) + 'px';
                    joystickHandle.setAttribute('aria-valuenow', '50');
                }
                joystickHandle.releasePointerCapture(e.pointerId);
            };

            joystickHandle.addEventListener('keydown', (e) => {
                let v = 0;
                if (e.key === 'ArrowUp'   || e.key === 'ArrowRight' || e.key === '+' || e.key === '=') v =  0.5;
                else if (e.key === 'ArrowDown' || e.key === 'ArrowLeft'  || e.key === '-' || e.key === '_') v = -0.5;
                else if (e.key === 'PageUp')   v =  1.0;
                else if (e.key === 'PageDown') v = -1.0;
                if (v !== 0) {
                    e.preventDefault();
                    zoomVelocity = Math.sign(v) * (v * v) * 0.15;
                    const h = joystickContainer.offsetHeight || 160;
                    const t = (h / 2) - (v * (h / 2));
                    joystickHandle.style.top = `${t - 18}px`;
                    joystickHandle.setAttribute('aria-valuenow', Math.round(((h - t) / h) * 100).toString());
                }
            });

            joystickHandle.addEventListener('keyup', () => {
                if (!isDraggingJoystick) {
                    zoomVelocity = 0;
                    joystickHandle.style.top = (joystickContainer.offsetHeight / 2 - 18) + 'px';
                    joystickHandle.setAttribute('aria-valuenow', '50');
                }
            });
        }

        // --- FETCH ROOM LIST (populate switcher if job has multiple rooms) ---
        try {
            const response = await fetch(`/api/job/${encodeURIComponent(jobCode)}`);
            const data = await response.json();
            if (data.success && data.rooms && data.rooms.length > 1) {
                const switcher = document.getElementById('room-switcher-mini');
                const listUi   = document.getElementById('room-list-ui');
                if (switcher && listUi) {
                    switcher.style.display = 'block';
                    data.rooms.forEach(r => {
                        const btn = document.createElement('button');
                        btn.innerText = r;
                        btn.className = 'room-switcher-btn';
                        const isActive = r === initialRoom;
                        btn.style.cssText = `background:${isActive ? '#1976d2' : 'rgba(40,40,40,0.85)'}; color:${isActive ? '#fff' : '#ddd'};`;
                        if (lightMode) {
                            btn.style.cssText = `background:${isActive ? '#1976d2' : 'rgba(240,240,240,0.9)'}; color:${isActive ? '#fff' : '#222'};`;
                        }
                        btn.onclick = () => {
                            window.location.href = `viewer.html?job=${encodeURIComponent(jobCode)}&room=${encodeURIComponent(r)}&dark=${lightMode ? '0' : '1'}`;
                        };
                        listUi.appendChild(btn);
                    });
                }
            }
        } catch (e) {
            console.warn('Room list fetch failed:', e);
        }

        // --- FETCH MODEL URL ---
        const urlRes  = await fetch(`/api/job/${encodeURIComponent(jobCode)}/${encodeURIComponent(initialRoom)}`);
        const urlData = await urlRes.json();
        if (!urlData.success || !urlData.url) throw new Error("Model URL not found for this room");

        const maxAnisotropy = renderer.capabilities.getMaxAnisotropy();

        updateStatus("Loading Model...");

        const { model } = await loadModel(
            urlData.url,
            maxAnisotropy,
            (xhr) => {
                if (xhr.lengthComputable) {
                    updateStatus(`Downloading: ${Math.round((xhr.loaded / xhr.total) * 100)}%`);
                }
            }
        );

        if (loadedModel) { scene.remove(loadedModel); disposeModel(loadedModel); }
        loadedModel = model;
        scene.add(loadedModel);
        frameLoadedModel(loadedModel);

        if (composer) composer.render();
        else renderer.render(scene, camera);

        updateStatus("");

        setTimeout(() => {
            if (renderer && scene && camera) {
                scene.traverse((obj) => { if (obj.isMesh && obj.material) obj.material.needsUpdate = true; });
                if (composer) composer.render();
                else renderer.render(scene, camera);
            }
        }, 100);

    } catch (e) {
        console.error(e);
        updateStatus("Load Error: " + e.message, true);
    }

    engine.start();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
