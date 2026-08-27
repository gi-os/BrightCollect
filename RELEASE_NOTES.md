## v1.1 — a tray, a guess, and stickers that stay stickers

**The shelf is a tray now.** A grid says every cell is the same and the contents are
interchangeable, which is the opposite of what a collection claims. Each sticker takes its own
trimmed bounding box, normalised by area rather than width so a pencil and a plate each read as
one object, and the boxes are packed by a skyline so they interlock instead of sitting in rows.
Each leans a few degrees, seeded from its own id so the tray survives scrolling and restarts
rather than reshuffling every time it is drawn.

Something genuinely long — a pencil, a belt — spans the tray. The first version had a long-edge
cap and a short-edge floor fighting each other, and the cap always won, so anything past about
3:1 came out as a four-pixel hairline. The floor goes first now and the cap is allowed to
decline; only the tray's width is absolute.

**A name to start from.** A bundled labeller runs on the finished cutout and prefills the name,
with the whole field selected so the first keystroke replaces it. It labels the cutout rather
than the photograph, which is most of why it works: the background is already gone, so the
table and the wall are not there to be named instead. Below 55% confidence it says nothing and
you get the numbered default.

**Stickers stop pretending to be photographs.** They used to be published into MediaStore as
flattened JPEGs on white so BrightNotebook's calendar would find them. That worked and was
visibly wrong — the notebook drew them in the photo strip on a white card, and Roll's grid
filled up with white-background duplicates of things already in the collection.

There is a provider instead, in the shape LightFog, LightChat, BrightRecorder, BrightWay and
LightBooks already use with the notebook: asked by date, no permission on either side, and it
serves the real PNG with its alpha. **Update BrightNotebook too** — v2.x draws the day's catches
as a little tray of cutouts, no card and no frame, and tapping one opens it back here.

Stickers already published into MediaStore by v1.0 are left alone. Delete the
`Pictures/Collect` album if you want them gone.

**Size:** the APK is 47 MB, up from 34. The labeller's model is 2.9 MB of that; the rest is ML
Kit's own inference runtime sitting beside the one the cutout already uses.
