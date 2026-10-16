# Cabinet Vision door textures

Door thumbnails, expanded previews, and fullscreen cabinet/door views read the shared library at
`<Ready Jobs>/.metadata/material_textures/manifest.json` (schema version 1). This is a global
library, independent of each job's `.metadata/cv_job/job_data.json`. The Android app only reads it.
The initial library was exported from CVData and the Cabinet Vision Graphics folder on 2026-10-16;
no recurring catalog exporter is configured.

Published `parts[].materialId` joins a door component to `materials[].id`, then its face layer
(`typeId: 1`) to `textures[].id`. Legacy exports without an ID may use a unique exact material
name, ignoring case and surrounding whitespace. A published but unknown ID is not guessed by name.
Each visible member/panel uses its own component material, including multi-panel and sloped doors.

Texture entries provide the relative JXL `file`, `sha256`, `worldWidthMm`, `worldHeightMm`, and
`tile`. Paths must stay inside the library. The physical dimensions convert to inches for door
geometry and control the repeat size, independent of bitmap resolution. Stiles use lengthwise
vertical grain; rails rotate the image along their length, including sloped rails. Panel/slab grain
follows the published direction. Untiled images, or images without explicit physical dimensions,
fit the visible face rather than inventing a repeat size.

No assigned texture, an unknown/missing/unreadable image, or a texture named `Paint Grade` uses
pure white (`#FFFFFF`) without grain. The source filename `Cabinets.jpg` also enforces this rule,
case-insensitively, regardless of the catalog display name. CV's `Blank` and `No Texture` entries
mean no texture. These rules depend on the texture assignment, not the material's name or ID.
Glass retains the existing generated gradient and glints.

The UI checks the manifest and missing/replaced images every three seconds while composed.
Native JXL decoding runs serially off the main thread, samples images to a maximum 1024-pixel
edge, checks provided SHA-256 hashes, and uses a 24 MiB bitmap cache. Missing/partial sync data
stays white and recovers when valid files arrive. Other cabinet doors keep whole-door opacity at
72%; the selected door remains opaque. No source metadata or textures are written by the tablet.

Verification: `CvMaterialTexturesTest`, `DoorMaterialDrawingTest`, and the device tests
`DoorMaterialTextureTest`, `CabinetDoorTextureTest`, and `DoorPreviewFullscreenTest` cover the
contract, physical scaling/rotation, actual native decoding, white and glass rendering, sync
recovery, cabinet compositing, and inspection controls.
