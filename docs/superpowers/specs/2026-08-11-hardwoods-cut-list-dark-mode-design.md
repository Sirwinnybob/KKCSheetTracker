# Hardwoods Cut List Dark Mode Design

## Goal

Make the Hardwood Cut List control surfaces follow the active Material theme so they remain readable and visually consistent in dark mode.

## Scope

Update `HardwoodsWorkspaceScreen.kt` only. Replace hard-coded white control-surface colors in the Cut List document selector, List/Classic mode selector, its menu, and reference-document selector with their appropriate `MaterialTheme.colorScheme` roles.

The selected List/Classic mode must remain visually distinct. Existing semantic status controls (tally, skip, and clock-in colors) and all control behavior remain unchanged.

## Design

- Unselected control containers use `surface`.
- The mode menu uses the theme's surface-container role.
- Selected mode continues to use `secondaryContainer`.
- Text and icon colors continue to resolve through their existing Material theme defaults.

## Verification

Build the debug app with Gradle after the change. Inspect the changed composables to confirm no hard-coded white surface remains in the scoped controls.
