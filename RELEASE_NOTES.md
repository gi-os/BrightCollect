## v1.0 — Collect

The first one. Point the phone at a thing, and keep the thing rather than the photograph.

**Cutting out**

- A 4.4 MB U-2-Net (`u2netp`) runs in the APK through ONNX Runtime. No Play Services, no
  network, works with the radio off. About 300 ms a shot.
- The model's map is stretched to its observed range before it is thresholded, so a
  low-contrast subject does not come back as the whole frame or as nothing.
- Hardened before the upscale, feathered after it — the other order magnifies a 320-pixel
  blur into a visible band around the edge.

**Fixing it when it is wrong**

- **Wand.** One tap takes the whole contiguous region of similar colour under your finger —
  the table, the shadowed half of a mug. Adjustable similarity. The region is grown two pixels
  before it is committed, which is what removes the halo of background that otherwise clings
  to the edge.
- **Brush.** Freehand, soft-edged, for the edges the wand cannot describe.
- **KEEP / CUT** applies to both, so there are two controls rather than four tools.
- Eight steps of undo. A fill that would take more than 92% of the frame is refused rather
  than committed, because that one tap would otherwise wipe the mask.

**The collection**

- Stickers are PNGs with alpha in the app's own storage, plus a JSON index. LightSync backs up
  the whole thing — unlike Roll's photographs, a sticker exists nowhere else and carries
  hand-corrections that cannot be re-derived.
- Three-column shelf, scrollable with the wheel, and it lifts LightOS's greyscale so a
  collection looks like a collection. Needs one adb grant; see the README.

**Connected to the rest**

- **BrightChat**: SEND hands over a PNG with its alpha intact, ClipData and all, so the grant
  actually reaches the receiver.
- **BrightNotebook**: a flattened copy goes into MediaStore under the capture date, which is
  where the calendar already looks — no bridge, no permission on either side.
- **Roll**: Collect registers as a destination for a shared image, so anything on the roll can
  become a sticker. The EXIF capture time travels with it, so a picture from June lands on
  June's page.
- `brightcollect://sticker/<id>` opens one directly.
