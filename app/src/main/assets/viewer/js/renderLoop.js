// On-demand render scheduler. The loop only keeps re-arming requestAnimationFrame while `step`
// reports that another frame is needed (camera still moving/damping, user interacting, zoom
// joystick held). Otherwise it goes idle until `request()` is called again, so a visible but
// untouched 3D pane costs no GPU/CPU.
export class RenderLoop {
    constructor({
        step,
        raf = (cb) => requestAnimationFrame(cb),
        cancelRaf = (id) => cancelAnimationFrame(id)
    }) {
        this._step = step;
        this._raf = raf;
        this._cancelRaf = cancelRaf;
        this._rafId = null;
        this._running = false;
        this._suspended = false;
        this._tick = this._tick.bind(this);
    }

    get suspended() {
        return this._suspended;
    }

    start() {
        if (this._running) return;
        this._running = true;
        this.request();
    }

    request() {
        if (!this._running || this._suspended || this._rafId !== null) return;
        this._rafId = this._raf(this._tick);
    }

    suspend() {
        if (this._suspended) return;
        this._suspended = true;
        if (this._rafId !== null) {
            this._cancelRaf(this._rafId);
            this._rafId = null;
        }
    }

    resume() {
        if (!this._suspended) return;
        this._suspended = false;
        this.request();
    }

    _tick(time) {
        this._rafId = null;
        if (!this._running || this._suspended) return;
        if (this._step(time)) this.request();
    }
}
