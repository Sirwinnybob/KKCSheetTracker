# Corner Radius Reduction Edits (25% reduction)

All corner radius values have been reduced by approximately 25% to create a cleaner, more modern UI.

## Files Edited

### Core Theme Files
- **`app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeTokens.kt`**
  - Updated shape token values: small (8dp → 6dp), medium (14dp → 9dp), large (18dp → 12dp)

- **`app/src/main/java/com/kkc/sheettracker/ui/theme/Theme.kt`**
  - Updated KKCShapes fallback values to match theme tokens

### Components

- **`app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`**
  - Nav bar corner radius: 26dp → 20dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorOverlay.kt`**
  - Dialog shape: 20dp → 15dp
  - Chip shape: 14dp → 11dp
  - Status badge: 10dp → 8dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/ProgressPill.kt`**
  - Corner radius: 11dp → 9dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/ClockInButton.kt`**
  - Corner radius: 12dp → 9dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/SortToggleBar.kt`**
  - Toggle button: 12dp → 9dp
  - Track: 9dp → 7dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/JobBoardGrid.kt`**
  - Card shape: 8dp → 6dp
  - Label badge: 4dp → 3dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/AdminPasswordDialog.kt`**
  - Dialog shape: 22dp → 17dp
  - TextField shape: 14dp → 11dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`**
  - Error message pill: 8dp → 6dp

- **`app/src/main/java/com/kkc/sheettracker/ui/components/ClockInOverlay.kt`**
  - AlertDialog shape: 22dp → 17dp
  - Edge tab inner corners: 14dp → 11dp
  - Modal shape: 16dp → 12dp

### Timecard

- **`app/src/main/java/com/kkc/sheettracker/ui/timecard/TimecardScreen.kt`**
  - DisplayCard shadow + clip + Surface shape: 15dp → 10dp
  - NumpadKey shadow + clip: 10dp → 7dp
  - Action button shadow + clip: 10dp → 7dp

- **`app/src/main/java/com/kkc/sheettracker/ui/timecard/BgPickerSheet.kt`**
  - Option card shape: 12dp → 9dp

- **`app/src/main/java/com/kkc/sheettracker/ui/timecard/HsvColorPicker.kt`**
  - Color preview: 12dp → 9dp

### Hours

- **`app/src/main/java/com/kkc/sheettracker/ui/hours/HoursLoginDialog.kt`**
  - Dialog shape: 22dp → 17dp
  - TextField shape: 14dp → 11dp

### Dashboard

- **`app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt`**
  - Chip shape: 14dp → 11dp

- **`app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt`**
  - Inventory item row: 12dp → 9dp

### Hardwoods

- **`app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`**
  - Section header Surface: RoundedCornerShape(10.dp) → RoundedCornerShape(8.dp)
  - Admin material header Surface: already at 8dp (no change)
  - Admin board stock item row: RoundedCornerShape(6.dp) stays (already small)
  - Admin badge (NONE): RoundedCornerShape(4.dp) → RoundedCornerShape(3.dp)
  - MaterialSkipPill: RoundedCornerShape(9.dp) → RoundedCornerShape(7.dp)

### Supply

- **`app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyModalFrame.kt`**
  - Surface dialog shape: 18dp → 14dp

- **`app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTabReorderScreen.kt`**
  - DashboardSurfaceCard shape: 12dp → 9dp

- **`app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemEditScreen.kt`**
  - OutlinedCard (category, status): 12dp → 9dp
  - OutlinedTextField (name, notes, sku, quantity, vendorLink, trackingNumber): 6dp → 4dp

- **`app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt`**
  - Image clip: 12dp → 9dp
  - Camera/Gallery Card shape: 12dp → 9dp
  - DashboardSurfaceCard (add comment): 12dp → 9dp
  - OutlinedTextField shapes: 6dp → 4dp
  - DetailSection card: 12dp → 9dp
  - CommentCard: 12dp → 9dp

## Already Complete (No changes needed)
- **`app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTab.kt`** — Already uses correct corner radii
- **`app/src/main/java/com/kkc/sheettracker/ui/components/CoverPageOverlay.kt`** — Uses `RoundedCornerShape(50)` pill shape (no change needed)

## Summary
All component files have been updated to use the reduced corner radius values. The changes maintain visual consistency while creating a more modern, slightly sharper UI aesthetic. Build verified with `assembleDebug`.
