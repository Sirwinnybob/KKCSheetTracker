import { CoreEngine } from './engine.js';
import { loadModel } from './modelLoader.js';
// Resolved via importmap in viewer.html
import * as THREE from 'three';

let scene, camera, renderer, controls, engine;
let zoomVelocity = 0;
let loadedModel = null;
const zoomDirection = new THREE.Vector3();
const pointerMoveVector = new THREE.Vector2();

function withViewerBridge(action) {
    const bridge = window.KKCViewerBridge;
    if (!bridge) return;
    try {
        action(bridge);
    } catch (_) {
        // Ignore bridge failures; viewer should still function if host bridge is absent.
    }
}

function updateRenderVisibility() {
    if (!engine) return;
    if (document.hidden) {
        engine.suspendRendering();
    } else {
        engine.resumeRendering();
    }
}

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
            onViewerActiveChanged: (active) => {
                withViewerBridge((bridge) => bridge.setViewerActive(active));
            },
            onInteractionStateChanged: (interacting) => {
                withViewerBridge((bridge) => bridge.setViewerInteracting(interacting));
            },
            onBeforeRender: () => {
                let moved = false;
                if (zoomVelocity !== 0 && camera && controls) {
                    camera.getWorldDirection(zoomDirection);
                    const dist = camera.position.distanceTo(controls.target);
                    if (!(zoomVelocity > 0 && dist < 0.5)) {
                        camera.position.addScaledVector(zoomDirection, zoomVelocity * controls.zoomSpeed);
                        moved = true;
                    }
                }
                return moved;
            }
        });

        // Keep rendering fully paused while page is hidden to reduce battery use.
        document.addEventListener('visibilitychange', updateRenderVisibility);
        window.addEventListener('pagehide', updateRenderVisibility);
        window.addEventListener('pageshow', updateRenderVisibility);
        window.addEventListener('beforeunload', () => {
            withViewerBridge((bridge) => {
                bridge.setViewerInteracting(false);
                bridge.setViewerActive(false);
            });
        });
        updateRenderVisibility();

        scene    = engine.scene;
        camera   = engine.camera;
        renderer = engine.renderer;
        controls = engine.controls;
        // --- SINGLE TAP → PIVOT (consistent with Assimp app) ---
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
            pointerMoveVector.set(e.clientX, e.clientY);
            if (pointerDownPos.distanceTo(pointerMoveVector) > DRAG_THRESHOLD) {
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
            if (engine) engine.requestRender();
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
                if (engine) engine.requestRender();
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
                    if (engine) engine.requestRender();
                    const h = joystickContainer.offsetHeight || 160;
                    const t = (h / 2) - (v * (h / 2));
                    joystickHandle.style.top = `${t - 18}px`;
                    joystickHandle.setAttribute('aria-valuenow', Math.round(((h - t) / h) * 100).toString());
                }
            });

            joystickHandle.addEventListener('keyup', () => {
                if (!isDraggingJoystick) {
                    zoomVelocity = 0;
                    if (engine) engine.requestRender();
                    joystickHandle.style.top = (joystickContainer.offsetHeight / 2 - 18) + 'px';
                    joystickHandle.setAttribute('aria-valuenow', '50');
                }
            });
        }

        // --- ROOM MODEL LOADING (initial room and in-page room switches) ---
        // Switching rooms swaps the model in this same page/engine -- no reload -- so the room
        // selector, camera controls and WebGL context all stay alive. `roomLoadSeq` makes the
        // latest request win if the user taps through several rooms quickly.
        let currentRoom = initialRoom;
        let roomLoadSeq = 0;
        const loadRoomModel = async (room) => {
            const seq = ++roomLoadSeq;
            updateStatus("Loading Model...");
            const urlRes  = await fetch(`/api/job/${encodeURIComponent(jobCode)}/${encodeURIComponent(room)}`);
            const urlData = await urlRes.json();
            if (seq !== roomLoadSeq) return;
            if (!urlData.success || !urlData.url) throw new Error("Model URL not found for this room");

            const maxAnisotropy = renderer.capabilities.getMaxAnisotropy();
            const { model } = await loadModel(
                urlData.url,
                maxAnisotropy,
                (xhr) => {
                    if (seq === roomLoadSeq && xhr.lengthComputable) {
                        updateStatus(`Downloading: ${Math.round((xhr.loaded / xhr.total) * 100)}%`);
                    }
                }
            );
            if (seq !== roomLoadSeq) { disposeModel(model); return; }

            if (loadedModel) { scene.remove(loadedModel); disposeModel(loadedModel); }
            loadedModel = model;
            scene.add(loadedModel);
            frameLoadedModel(loadedModel);
            if (engine) engine.requestRender();

            updateStatus("");

            setTimeout(() => {
                if (renderer && scene && camera) {
                    scene.traverse((obj) => { if (obj.isMesh && obj.material) obj.material.needsUpdate = true; });
                    if (engine) engine.requestRender();
                }
            }, 100);
        };

        // --- ROOM LIST: vertical sliding-pill selector (only when the job has multiple rooms) ---
        try {
            const response = await fetch(`/api/job/${encodeURIComponent(jobCode)}`);
            const data = await response.json();
            const nativeBridge = window.KKCViewerBridge;
            if (data.success && data.rooms && data.rooms.length > 1 && nativeBridge && nativeBridge.setRooms) {
                // The app draws the room selector natively over this page (animating a pill inside
                // the WebView made the whole overlay flicker). Hand it the rooms and expose the
                // in-page switch for it to call.
                let startRoom = data.rooms.indexOf(initialRoom) >= 0
                    ? initialRoom
                    : (data.rooms.find((r) => String(r).trim().toLowerCase() === String(initialRoom).trim().toLowerCase()) || data.rooms[0]);
                window.kkcSelectRoom = (r) => {
                    currentRoom = r;
                    try {
                        const next = new URLSearchParams(window.location.search);
                        next.set('room', r);
                        history.replaceState(null, '', `viewer.html?${next.toString()}`);
                    } catch (e) { /* non-fatal */ }
                    loadRoomModel(r).catch((err) => {
                        console.error(err);
                        updateStatus("Load Error: " + err.message, true);
                    });
                };
                nativeBridge.setRooms(JSON.stringify(data.rooms), startRoom);
            } else if (data.success && data.rooms && data.rooms.length > 1) {
                const switcher = document.getElementById('room-switcher-mini');
                const listUi   = document.getElementById('room-list-ui');
                if (switcher && listUi) {
                    switcher.style.display = 'block';

                    // Theme from the app (AARRGGBB hex URL params); falls back to the CSS defaults.
                    const themeParams = new URLSearchParams(window.location.search);
                    const cssColor = (key) => {
                        const raw = themeParams.get(key);
                        if (!raw || !/^[0-9a-fA-F]{8}$/.test(raw)) return null;
                        const v = parseInt(raw, 16);
                        const a = ((v >>> 24) & 255) / 255;
                        return `rgba(${(v >>> 16) & 255}, ${(v >>> 8) & 255}, ${v & 255}, ${a.toFixed(3)})`;
                    };
                    [['t', '--room-track'], ['tb', '--room-track-border'], ['tt', '--room-track-text'],
                     ['p', '--room-pill'], ['pb', '--room-pill-border'], ['pt', '--room-pill-text']]
                        .forEach(([key, cssVar]) => {
                            const c = cssColor(key);
                            if (c) listUi.style.setProperty(cssVar, c);
                        });

                    const pill = document.createElement('div');
                    pill.id = 'room-pill';
                    listUi.appendChild(pill);

                    const buttons = [];
                    let activeBtn = null;
                    const placePill = (btn) => {
                        pill.style.height = btn.offsetHeight + 'px';
                        pill.style.top = btn.offsetTop + 'px';
                    };
                    const setActive = (btn) => {
                        buttons.forEach((b) => b.classList.toggle('active', b === btn));
                        activeBtn = btn;
                    };
                    const norm = (name) => String(name).trim().toLowerCase();

                    let switchTimer = null;
                    data.rooms.forEach((r) => {
                        const btn = document.createElement('button');
                        btn.innerText = r;
                        btn.className = 'room-switcher-btn';
                        btn.onclick = () => {
                            if (btn === activeBtn) return;
                            currentRoom = r;
                            setActive(btn);
                            pill.classList.add('animate');
                            placePill(btn);
                            // Keep the URL in step so a pane rebuild reopens the same room.
                            try {
                                const next = new URLSearchParams(window.location.search);
                                next.set('room', r);
                                history.replaceState(null, '', `viewer.html?${next.toString()}`);
                            } catch (e) { /* non-fatal */ }
                            // Everything that costs frames -- telling the app (it recomposes), the
                            // "Loading" overlay with its blur, and the model parse/upload -- waits
                            // until the slide has actually finished (transitionend, with a timer as
                            // a fallback), so the pill animates on an otherwise idle page.
                            clearTimeout(switchTimer);
                            let started = false;
                            const afterSlide = () => {
                                if (started) return;
                                started = true;
                                pill.removeEventListener('transitionend', afterSlide);
                                clearTimeout(switchTimer);
                                if (currentRoom !== r) return; // superseded by a later tap
                                withViewerBridge((bridge) => { if (bridge.onRoomSelected) bridge.onRoomSelected(r); });
                                setTimeout(() => {
                                    loadRoomModel(r).catch((err) => {
                                        console.error(err);
                                        updateStatus("Load Error: " + err.message, true);
                                    });
                                }, 80);
                            };
                            pill.addEventListener('transitionend', afterSlide);
                            switchTimer = setTimeout(afterSlide, 700);
                        };
                        buttons.push(btn);
                        listUi.appendChild(btn);
                    });

                    // Initial selection: exact match first, then a trimmed/case-insensitive one, so
                    // a room name that differs only in spacing/case still gets its highlight.
                    let startIdx = data.rooms.indexOf(initialRoom);
                    if (startIdx < 0) startIdx = data.rooms.findIndex((r) => norm(r) === norm(initialRoom));
                    if (startIdx < 0) startIdx = 0;
                    setActive(buttons[startIdx]);
                    placePill(buttons[startIdx]);
                    // Web fonts / late layout can change button heights after first paint.
                    const relayout = () => { if (activeBtn) placePill(activeBtn); };
                    window.addEventListener('resize', relayout);
                    if (document.fonts && document.fonts.ready) document.fonts.ready.then(relayout);
                }
            }
        } catch (e) {
            console.warn('Room list fetch failed:', e);
        }

        await loadRoomModel(initialRoom);

    } catch (e) {
        console.error(e);
        updateStatus("Load Error: " + e.message, true);
    }

    engine.start();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
