export class UIManager {
    constructor(options = {}) {
        this.options = options;
    }

    init() {
        this.setupMenu();
        this.setupHelp();
        this.setupLightMode();

        window.addEventListener('keydown', (e) => {
            const active = document.activeElement;
            const isInput = !!(active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.tagName === 'SELECT' || active.isContentEditable));

            if (e.key === 'Escape') {
                if (isInput) { active.blur(); return; }
                this.closeActiveModals();
                return;
            }

            if (isInput || e.ctrlKey || e.altKey || e.metaKey) return;

            const shortcuts = {
                m: 'menu-btn',
                l: 'light-mode-btn',
                h: 'help-btn',
                '?': 'help-btn'
            };
            const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
            if (shortcuts[key]) {
                const btn = document.getElementById(shortcuts[key]);
                if (btn) { e.preventDefault(); btn.click(); }
                return;
            }

            const zoomKeys = ['+', '=', '-', '_', 'PageUp', 'PageDown'];
            if (zoomKeys.includes(e.key)) {
                const joystick = document.getElementById('joystick-handle');
                if (joystick && document.activeElement !== joystick) {
                    joystick.dispatchEvent(new KeyboardEvent('keydown', { key: e.key, bubbles: false }));
                }
            }
        });

        window.addEventListener('keyup', (e) => {
            const zoomKeys = ['+', '=', '-', '_', 'PageUp', 'PageDown'];
            if (zoomKeys.includes(e.key)) {
                const joystick = document.getElementById('joystick-handle');
                if (joystick && document.activeElement !== joystick) {
                    joystick.dispatchEvent(new KeyboardEvent('keyup', { key: e.key, bubbles: false }));
                }
            }
        });
    }

    setupMenu() {
        const menuBtn = document.getElementById('menu-btn');
        const dropdown = document.getElementById('dropdown-menu');

        if (menuBtn && dropdown) {
            menuBtn.onclick = (e) => {
                e.stopPropagation();
                dropdown.classList.toggle('show');
                const isExpanded = dropdown.classList.contains('show');
                menuBtn.setAttribute('aria-expanded', isExpanded.toString());
            };
            window.addEventListener('pointerdown', (e) => {
                const menuContainer = document.getElementById('menu-container');
                if (menuContainer && !menuContainer.contains(e.target)) {
                    dropdown.classList.remove('show');
                    menuBtn.setAttribute('aria-expanded', 'false');
                }
            });
        }
    }

    setupHelp() {
        const helpBtn = document.getElementById('help-btn');
        const helpModal = document.getElementById('help-modal');
        const closeHelpX = document.getElementById('close-help-x');
        const closeHelpBtn = document.getElementById('close-help-btn');

        this.toggleHelp = (show) => {
            if (helpModal) {
                helpModal.classList.toggle('show', show);
                if (show) {
                    if (closeHelpBtn) closeHelpBtn.focus();
                } else {
                    if (helpBtn) helpBtn.focus();
                }
            }
        };

        if (helpBtn) helpBtn.onclick = () => this.toggleHelp(true);
        if (closeHelpX) closeHelpX.onclick = () => this.toggleHelp(false);
        if (closeHelpBtn) closeHelpBtn.onclick = () => this.toggleHelp(false);
        if (helpModal) {
            helpModal.addEventListener('click', (e) => {
                if (e.target === helpModal) this.toggleHelp(false);
            });
        }
    }

    setupLightMode() {
        const lightModeBtn = document.getElementById('light-mode-btn');
        if (lightModeBtn) {
            const sunSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>`;
            const moonSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>`;

            const updateLightModeUI = () => {
                const isLightMode = localStorage.getItem("lightMode") === "true";
                lightModeBtn.style.background = isLightMode ? '#e0e0e0' : '#fff';
                lightModeBtn.innerHTML = isLightMode ? moonSvg : sunSvg;
            };

            lightModeBtn.addEventListener('click', () => {
                const isLightMode = localStorage.getItem("lightMode") === "true";
                const newMode = !isLightMode;
                localStorage.setItem("lightMode", newMode);
                updateLightModeUI();
                window.dispatchEvent(new CustomEvent('lightmodechange', { detail: { isLightMode: newMode } }));
            });

            updateLightModeUI();
        }
    }

    closeActiveModals() {
        const helpModal = document.getElementById('help-modal');
        if (helpModal?.classList.contains('show')) {
            document.getElementById('close-help-btn')?.click();
            return true;
        }
        const dropdown = document.getElementById('dropdown-menu');
        if (dropdown?.classList.contains('show')) {
            document.getElementById('menu-btn')?.click();
            return true;
        }
        return false;
    }
}
