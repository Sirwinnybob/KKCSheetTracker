import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js';
import { FXAAShader } from 'three/addons/shaders/FXAAShader.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { RenderLoop } from './renderLoop.js';

const ANTIALIASING_SAMPLES = { off: 0, low: 0, medium: 2, high: 4 };

function normalizeAntialiasingQuality(quality) {
    return Object.prototype.hasOwnProperty.call(ANTIALIASING_SAMPLES, quality) ? quality : 'high';
}

/** Lowers the drawing buffer to a fraction of the device resolution while CSS keeps it full-size. */
export function scaledPixelRatio(devicePixelRatio, renderScale = 0.75, maxDevicePixelRatio = 2) {
    const nativeRatio = Number(devicePixelRatio);
    const native = Math.min(Math.max(Number.isFinite(nativeRatio) ? nativeRatio : 1, 0.1), maxDevicePixelRatio);
    const requestedScale = Number(renderScale);
    const scale = Math.min(Math.max(Number.isFinite(requestedScale) ? requestedScale : 1, 0.1), 1);
    return native * scale;
}

export class CoreEngine {
    constructor(config = {}) {
        this.config = Object.assign({
            containerId: 'canvas-container',
            isLightMode: false,
            fov: 45,
            exposure: 1.15,
            saturation: 0.65,
            contrast: 1.50,
            lightIntensity: 1.0,
            gloss: 0.10,
            colorTemp: 0.5,
            msaaSamples: 0,
            renderScale: 0.75,
            onBeforeRender: null,
            onInteractionStateChanged: null,
            onViewerActiveChanged: null
        }, config);

        const initialSamples = this.config.msaaSamples;
        this.antialiasingQuality = initialSamples >= 4 ? 'high' : initialSamples >= 2 ? 'medium' : initialSamples >= 1 ? 'low' : 'off';
        this.antialiasingActive = true;

        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(this.config.isLightMode ? 0xf0f0f0 : 0x111111);

        this.camera = new THREE.PerspectiveCamera(this.config.fov, window.innerWidth / window.innerHeight, 0.01, 5000);

        // Keep this aligned with the known-smooth Assimp portal runtime.
        this.renderer = new THREE.WebGLRenderer({
            antialias: true,
            powerPreference: 'high-performance',
            logarithmicDepthBuffer: true,
            preserveDrawingBuffer: true
        });

        const dpr = this._renderPixelRatio();
        this.renderer.setPixelRatio(dpr);
        this.renderer.setSize(window.innerWidth, window.innerHeight);

        const canvasContainer = document.getElementById(this.config.containerId);
        if (canvasContainer) {
            canvasContainer.appendChild(this.renderer.domElement);
            this.renderer.domElement.id = 'main-canvas';
            this.renderer.domElement.setAttribute('tabindex', '0');
            this.renderer.domElement.setAttribute('aria-label', '3D Model Viewer. Use arrow keys to rotate, shift + arrow keys to pan, and plus or minus keys to zoom.');
        }

        this.scene.add(this.camera);

        this.controls = new OrbitControls(this.camera, this.renderer.domElement);
        this.controls.listenToKeyEvents(window);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.25;
        this._interacting = false;
        this.controls.addEventListener('start', () => {
            this._interacting = true;
            this.requestRender();
            if (typeof this.config.onInteractionStateChanged === 'function') {
                this.config.onInteractionStateChanged(true);
            }
        });
        this.controls.addEventListener('end', () => {
            this._interacting = false;
            this.requestRender();
            if (typeof this.config.onInteractionStateChanged === 'function') {
                this.config.onInteractionStateChanged(false);
            }
        });
        // Wheel/key/touch handlers call controls.update() directly and fire 'change' when the
        // camera moved; wake the on-demand loop so that frame (and any damping tail) is drawn.
        this.controls.addEventListener('change', () => this.requestRender());

        this._cameraMotionListeners = new Set();
        this._lastCameraMatrix = null;

        this._setupLighting();
        this._setupPostProcessing(dpr);

        this._loop = new RenderLoop({ step: (time) => this._renderFrame(time) });

        window.addEventListener('resize', this._onWindowResize.bind(this));
    }

    _setupLighting() {
        const li = this.config.lightIntensity;
        this.scene.add(new THREE.AmbientLight(0xffffff, li * 1.2));

        const makeCamLight = (intensity, px, py, pz) => {
            const light = new THREE.DirectionalLight(0xffffff, intensity);
            const target = new THREE.Object3D();
            light.position.set(px, py, pz);
            this.camera.add(light);
            this.camera.add(target);
            light.target = target;
        };
        makeCamLight(li * 0.5, 1, 1, 1);

        const makeSceneLight = (intensity, px, py, pz) => {
            const light = new THREE.DirectionalLight(0xffffff, intensity);
            light.position.set(px, py, pz);
            this.scene.add(light);
        };
        makeSceneLight(li * 0.22, 2, 1, 0);
        makeSceneLight(li * 0.22, -2, 1, 0);
        makeSceneLight(li * 0.22, 0, 1, 2);
        makeSceneLight(li * 0.22, 0, 1, -2);
        makeSceneLight(li * 0.2, 0, -1, 0);
    }

    _setupPostProcessing(dpr) {
        this.composer = new EffectComposer(this.renderer);
        // Keep the wing viewer's explicit sample setting while sizing targets in logical pixels.
        const samples = Math.min(Math.max(this.config.msaaSamples, 0), this.renderer.capabilities.maxSamples || 0);
        this.composer.renderTarget1.samples = samples;
        this.composer.renderTarget2.samples = samples;
        this.composer.addPass(new RenderPass(this.scene, this.camera));

        const KKCShader = {
            uniforms: {
                tDiffuse: { value: null },
                uExposure: { value: this.config.exposure },
                uSaturation: { value: this.config.saturation },
                uContrast: { value: this.config.contrast },
                uColorTemp: { value: this.config.colorTemp }
            },
            vertexShader: `
                varying vec2 vUv;
                void main() {
                    vUv = uv;
                    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
                }
            `,
            fragmentShader: `
                uniform sampler2D tDiffuse;
                uniform float uExposure;
                uniform float uSaturation;
                uniform float uContrast;
                uniform float uColorTemp;
                varying vec2 vUv;

                void main() {
                    vec4 tex = texture2D(tDiffuse, vUv);
                    vec3 color = tex.rgb * uExposure;
                    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                    color = mix(vec3(luma), color, uSaturation);

                    // Shadow lift: prevents dark textures from crushing to black at steep angles.
                    color = color * 0.92 + 0.08;
                    // Smooth contrast curve.
                    color = smoothstep(0.5 - (0.5 / uContrast), 0.5 + (0.5 / uContrast), color);

                    vec3 warm = vec3(1.0, 0.9, 0.8);
                    vec3 neutral = vec3(1.0, 1.0, 1.0);
                    vec3 cool = vec3(0.8, 0.9, 1.0);
                    vec3 tempTint;
                    if (uColorTemp < 0.5) {
                        tempTint = mix(warm, neutral, uColorTemp * 2.0);
                    } else {
                        tempTint = mix(neutral, cool, (uColorTemp - 0.5) * 2.0);
                    }
                    color *= tempTint;
                    gl_FragColor = vec4(color, tex.a);
                }
            `
        };

        this.kkcShader = new ShaderPass(KKCShader);
        this.composer.addPass(this.kkcShader);

        this.fxaaPass = new ShaderPass(FXAAShader);
        this.fxaaPass.material.uniforms.resolution.value.x = 1 / (window.innerWidth * dpr);
        this.fxaaPass.material.uniforms.resolution.value.y = 1 / (window.innerHeight * dpr);
        this.composer.addPass(new OutputPass());
        this.composer.addPass(this.fxaaPass);
    }

    setAntialiasingQuality(quality) {
        this.antialiasingQuality = normalizeAntialiasingQuality(quality);
        this._applyAntialiasing();
    }

    /** Anti-aliasing runs only on the idle path; camera motion uses the cheap render target. */
    setAntialiasingActive(active) {
        this.antialiasingActive = active !== false;
        this._applyAntialiasing();
    }

    _applyAntialiasing() {
        if (!this.composer) return;
        const quality = normalizeAntialiasingQuality(this.antialiasingQuality);
        this.antialiasingQuality = quality;
        const enabled = this.antialiasingActive !== false && quality !== 'off';
        const maxSamples = this.renderer.capabilities.isWebGL2 ? this.renderer.capabilities.maxSamples : 0;
        const samples = enabled ? Math.min(ANTIALIASING_SAMPLES[quality], maxSamples) : 0;
        for (const target of [this.composer.renderTarget1, this.composer.renderTarget2]) {
            if (target.samples === samples) continue;
            target.samples = samples;
            target.dispose();
        }
        this.fxaaPass.enabled = enabled;
        this.requestRender();
    }

    _renderPixelRatio() {
        return scaledPixelRatio(window.devicePixelRatio, this.config.renderScale);
    }

    _onWindowResize() {
        const width = window.innerWidth;
        const height = window.innerHeight;
        const dpr = this._renderPixelRatio();

        this.camera.aspect = width / height;
        this.camera.updateProjectionMatrix();

        this.renderer.setPixelRatio(dpr);
        this.renderer.setSize(width, height);
        this.composer.setPixelRatio(dpr);
        this.composer.setSize(width, height);

        if (this.fxaaPass) {
            this.fxaaPass.material.uniforms.resolution.value.x = 1 / (width * dpr);
            this.fxaaPass.material.uniforms.resolution.value.y = 1 / (height * dpr);
        }
        this.requestRender();
    }

    setResolution(width, height, dpr) {
        this.renderer.setPixelRatio(dpr || 1);
        this.renderer.setSize(width, height, false);
        this.composer.setPixelRatio(this.renderer.getPixelRatio());
        this.composer.setSize(width, height);
        this.camera.aspect = width / height;
        this.camera.updateProjectionMatrix();

        if (this.fxaaPass) {
            const actualDpr = this.renderer.getPixelRatio();
            this.fxaaPass.material.uniforms.resolution.value.x = 1 / (width * actualDpr);
            this.fxaaPass.material.uniforms.resolution.value.y = 1 / (height * actualDpr);
        }
        this.requestRender();
    }

    requestRender() {
        this._loop.request();
    }

    /** Reports whether the camera changed on each rendered frame. Returns an unsubscribe function. */
    onCameraMotion(listener) {
        this._cameraMotionListeners.add(listener);
        return () => this._cameraMotionListeners.delete(listener);
    }

    _reportCameraMotion() {
        this.camera.updateMatrixWorld();
        const current = this.camera.matrixWorld.elements;
        const moving = cameraMatrixChanged(this._lastCameraMatrix, current);
        if (moving) {
            if (!this._lastCameraMatrix) this._lastCameraMatrix = new Float64Array(16);
            this._lastCameraMatrix.set(current);
        }
        this._cameraMotionListeners.forEach(listener => listener(moving));
        return moving;
    }

    // Returns true when another frame is needed (camera moving/damping, drag in progress, or
    // zoom joystick held); otherwise the loop goes idle until requestRender() is called.
    _renderFrame(time) {
        let needsAnotherFrame = this._interacting;
        if (this.config.onBeforeRender && this.config.onBeforeRender(time) === true) {
            needsAnotherFrame = true;
        }
        if (this.controls && this.controls.update()) needsAnotherFrame = true;
        // Render one frame after the last movement so observers can measure a settled camera.
        if (this._reportCameraMotion()) needsAnotherFrame = true;
        if (this.composer) this.composer.render();
        return needsAnotherFrame;
    }

    suspendRendering() {
        if (this._loop.suspended) return;
        this._loop.suspend();
        this._interacting = false;
        if (typeof this.config.onInteractionStateChanged === 'function') {
            this.config.onInteractionStateChanged(false);
        }
        if (typeof this.config.onViewerActiveChanged === 'function') {
            this.config.onViewerActiveChanged(false);
        }
    }

    resumeRendering() {
        if (!this._loop.suspended) return;
        if (typeof this.config.onViewerActiveChanged === 'function') {
            this.config.onViewerActiveChanged(true);
        }
        this._loop.resume();
    }

    start() {
        if (this._started) return;
        this._started = true;
        if (typeof this.config.onViewerActiveChanged === 'function') {
            this.config.onViewerActiveChanged(!this._loop.suspended);
        }
        this._loop.start();
    }
}

/** Compares camera world matrices so joystick zooms and damping tails also count as motion. */
export function cameraMatrixChanged(previous, current, epsilon = 1e-6) {
    if (!previous) return true;
    for (let i = 0; i < 16; i++) {
        if (Math.abs(current[i] - previous[i]) > epsilon) return true;
    }
    return false;
}

