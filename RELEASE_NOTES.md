## v1.4 — a real editor

**Nothing disconnected ends up in a sticker.** The model is a saliency detector, not an object
detector: it scores each pixel on how much it stands out, with no notion that the answer should
be one thing. So a mug on a worktop routinely came back as the mug *plus* a bright speck of tap
and a corner of tile — each as confident as the mug, which is why no amount of adjusting removed
them. They are dropped now by connectivity rather than by confidence: label the regions, keep the
largest, throw the rest away. Corner-touching does not count as joined, and the cleanup happens
before the edge is softened, because softening turns a speck into a faint tail that can reach the
object and make the two look connected.

It does **not** run after your own edits — a region you brushed on separately is deliberate — so
there is a **TIDY** button for when a session has picked up strays.

**Zoom.** Two fingers to zoom and pan; one finger is still the tool. The wheel held in zooms too.
Painting a two-pixel edge with a finger is impossible at fit-to-screen and easy at eight times,
which is most of what was wrong with the brush. The picture cannot be dragged off the screen, and
pinching holds the point under your fingers rather than sliding away from them. The brush keeps
its real size as you zoom, so what you set is what you get.

**The picture gets the screen.** Two rows of buttons and a stepper used to leave the photograph
about half the panel — on the one screen whose whole job is letting you see an edge. The controls
are one bar of short words now, and the two continuous values, brush size and wand similarity,
are on the **wheel**. Turning it costs no screen.

**SEE** shows the actual cutout on a checkerboard, alpha and all. The old ghosted view could not
show you an edge: soft and hard look identical when everything is 18% visible.

**Stickers stay editable.** REDO on a sticker reopens it with the original photograph and your
mask exactly as you left it — every fill and stroke still there — rather than starting over from
the model, which would throw away the corrections you made. A re-cut keeps its name and the day
you caught it instead of becoming a second sticker.

This costs about 400 KB a sticker for the photograph and mask kept beside it, so a collection of
two hundred is roughly 120 MB. Both are in the LightSync backup: a restore that brought back the
collection but not the ability to fix it would quietly downgrade every sticker in it.

Stickers cut before this version have no source kept, so REDO is greyed out on them.

Redo now sits beside undo.
