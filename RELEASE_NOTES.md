## v1.2 — the whole photo, four across, and a name you can just type

**The cut screen was hiding part of the photograph.** The canvas derived its height from its
width, which is fine until the box is not that tall — so a portrait photo overflowed and lost
its top and bottom. The part you could not see was also the part you could not paint on, and
the wand was aiming through the same wrong assumption.

The picture is fitted inside the screen now, letterboxed, at any shape. Taps map through the
same rectangle the picture is drawn in, so what you touch is what you hit. A tap in the margin
beside the photo does nothing, rather than landing on the outermost row of pixels — which is
background, so one stray tap used to take the entire background and read as a miss.

The brush preview ring was also sized against the canvas rather than the picture, so it lied
about the brush by whatever the fit happened to be. Fixed with the same rectangle.

**Four across instead of three.** The tray was leaving a lot of screen empty. Density is one
number now, expressed as a fraction of the tray's width so it holds on any screen, and the gap
and the minimum sticker size came down with it. The tray is 77% full where it was 72%, and
roughly half as tall for the same collection.

**Tapping a suggested name empties it.** It used to open with the whole name selected so your
first keystroke would replace it — true right up until you tapped the field, because a tap
places the caret and drops the selection. So the one gesture that means "I want to change this"
was the gesture that threw the shortcut away. Now it clears on the first tap, only while the
name is still a guess, and leaving it empty keeps the name it had.

Names you typed yourself are untouched: tapping one puts the caret where you tapped.
