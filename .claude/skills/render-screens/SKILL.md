---
name: render-screens
description: Render the app's screens to PNG images from the real analyser on synthetic recordings, to look at a UI change without a phone. Use after changing a screen or when asked how something looks.
---

# Render the screens

1. Run `scripts/screens.sh` (about 2–3 minutes; output in `build/screens/`). It runs `ScreenshotsTest` (opt-in,
   Robolectric native graphics) and lists the PNGs: `session`, `attempt-clean`, `attempt-noisy`, `attempt-tap`,
   `attempt-loud`, `session-details`, `room`, `compare`. Do not write a new throwaway render test: add a scene to
   `ScreenshotsTest` instead if a screen is missing.
2. Look at **one or two** images with Read, not all of them. They are tall (about 6000 px): crop with
   `python3 -I` and Pillow to the part that changed before reading (a 1233 px wide crop of about 2000 px high is
   plenty), otherwise they are downscaled to unreadable.
3. What it cannot show: dialogs, sheets and menus (separate windows). For those, check the texts with a UI test.
4. The recordings are synthetic. Say so when reporting what you saw; never present it as a real room.
5. Do not commit the PNGs (`build/` is ignored).
