# Hardwoods Cut List Dark Mode Design

## Goal

Make the Hardwood Cut List control surfaces follow the active Material theme so they remain readable and visually consistent in dark mode.

## Scope

Update `HardwoodsWorkspaceScreen.kt` and `HardwoodsRowVisuals.kt`. Replace hard-coded white control-surface colors in the Cut List document selector, List/Classic mode selector, its menu, and reference-document selector with their appropriate `MaterialTheme.colorScheme` roles.

The selected List/Classic mode must remain visually distinct. A skipped List View row must use a very faint yellow/orange wash consistent with the Classic View, without changing its skip border, progress treatment, button, or behavior. Other semantic status controls remain unchanged.

## Design

- Unselected control containers use `surface`.
- The mode menu uses the theme's surface-container role.
- Selected mode continues to use `secondaryContainer`.
- Text and icon colors continue to resolve through their existing Material theme defaults.
- Skipped List View rows use `status.skipBg` at 8% opacity. This avoids overwriting the embedded alpha in `skipBgRow`, which currently creates an overly strong fill.

## Verification

Add a regression test for the skipped-row wash opacity, then build the debug app with Gradle. Inspect the changed composables to confirm no hard-coded white surface remains in the scoped controls.
