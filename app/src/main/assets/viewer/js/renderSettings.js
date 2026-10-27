const STORAGE_KEY = 'kkc_viewer_render_settings';
const QUALITIES = ['off', 'low', 'medium', 'high'];

function normalize(value) {
    return {
        showOutlines: value?.showOutlines !== false,
        antialiasingQuality: QUALITIES.includes(value?.antialiasingQuality) ? value.antialiasingQuality : 'high'
    };
}

export function readRenderSettings(storage, bridge) {
    // Android's local server uses a new port when reopened. Native preferences are origin-independent.
    try {
        if (bridge?.getRenderSettings) return normalize(JSON.parse(bridge.getRenderSettings()));
    } catch (_) { /* Standalone/local storage recovery. */ }
    try { return normalize(JSON.parse(storage?.getItem(STORAGE_KEY) || '{}')); }
    catch (_) { return normalize(null); }
}

export function setupRenderSettings(engine, outlines, options = {}) {
    const elements = options.elements || document;
    const bridge = options.bridge || globalThis.KKCViewerBridge;
    let storage = options.storage;
    if (storage === undefined) {
        try { storage = globalThis.localStorage; } catch (_) { /* Storage may be disabled. */ }
    }
    const settings = readRenderSettings(storage, bridge);
    outlines.setEnabled(settings.showOutlines);
    engine.setAntialiasingQuality(settings.antialiasingQuality);
    const toggle = elements.getElementById('mesh-edges-toggle');
    const quality = elements.getElementById('antialiasing-quality');
    const panel = elements.getElementById('viewer-render-settings');
    const save = () => {
        try { bridge?.setRenderSettings?.(settings.showOutlines, settings.antialiasingQuality); } catch (_) { /* Local recovery. */ }
        try { storage?.setItem(STORAGE_KEY, JSON.stringify(settings)); } catch (_) { /* Session controls still work. */ }
    };
    const changeOutlines = () => {
        settings.showOutlines = toggle.checked;
        outlines.setEnabled(settings.showOutlines);
        engine.requestRender();
        save();
    };
    const changeQuality = () => {
        settings.antialiasingQuality = normalize({ antialiasingQuality: quality.value }).antialiasingQuality;
        engine.setAntialiasingQuality(settings.antialiasingQuality);
        engine.requestRender();
        save();
    };
    if (toggle) { toggle.checked = settings.showOutlines; toggle.addEventListener('change', changeOutlines); }
    if (quality) { quality.value = settings.antialiasingQuality; quality.addEventListener('change', changeQuality); }
    if (panel) panel.hidden = false;
    return { dispose() {
        toggle?.removeEventListener('change', changeOutlines);
        quality?.removeEventListener('change', changeQuality);
    } };
}
