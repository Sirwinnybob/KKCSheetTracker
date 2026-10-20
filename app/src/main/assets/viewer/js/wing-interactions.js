const TAP_THRESHOLD_PX = 8;

export function isTapGesture(start, end) {
  return Math.hypot(end.x - start.x, end.y - start.y) <= TAP_THRESHOLD_PX;
}

export function zoomVelocityForJoystick(relativeY, height) {
  const center = height / 2;
  if (center <= 0) return 0;
  const rawInput = (center - relativeY) / center;
  return Math.sign(rawInput) * (rawInput * rawInput) * 0.15;
}

export function wingControlAction(controlId) {
  return {
    'wing-frame-btn': 'fit',
    'wing-reset-btn': 'reset',
    'wing-caps-btn': 'toggleCapInspection'
  }[controlId];
}
