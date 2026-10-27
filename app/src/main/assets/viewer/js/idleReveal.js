// Expensive tablet GPU work (outlines, anti-aliasing) stays off while the camera moves and is
// only enabled after the view has been still for `idleDelayMs`.
export class IdleReveal {
    constructor({
        idleDelayMs = 1000,
        schedule = (callback, delay) => setTimeout(callback, delay),
        cancel = (timer) => clearTimeout(timer),
        requestRender = null
    } = {}) {
        this.idleDelayMs = idleDelayMs;
        this.schedule = schedule;
        this.cancel = cancel;
        this.requestRender = requestRender;
        this.targets = new Set();
        this.visible = false;
        this.revealPending = false;
        this.timer = null;
        this.disposed = false;
    }

    /** Applies the current reveal state and keeps it in sync on every frame. */
    register(applyVisibility) {
        this.targets.add(applyVisibility);
        applyVisibility(this.visible);
        return () => this.targets.delete(applyVisibility);
    }

    /** Engine frame report: `moving` is true when the camera changed this frame. */
    frame(moving) {
        if (this.disposed) return;
        if (moving) {
            this._cancelTimer();
            this.setVisible(false);
            return;
        }
        if (this.visible) return;
        if (this.revealPending) {
            this.revealPending = false;
            this.setVisible(true);
            return;
        }
        if (this.timer !== null) return;
        this.timer = this.schedule(() => {
            this.timer = null;
            this.revealPending = true;
            if (this.requestRender) this.requestRender();
        }, this.idleDelayMs);
    }

    setVisible(visible) {
        this.revealPending = false;
        if (this.visible === visible) return;
        this.visible = visible;
        this.targets.forEach(apply => apply(visible));
    }

    _cancelTimer() {
        if (this.timer === null) return;
        this.cancel(this.timer);
        this.timer = null;
    }

    dispose() {
        this.disposed = true;
        this._cancelTimer();
        this.targets.clear();
    }
}
