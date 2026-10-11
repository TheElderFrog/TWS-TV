# Changelog

## beta-4.16.4-tv8 - 2026-10-11

- Include the tv7 detail navigation fixes: select media initially; Left opens the previous image, Right enters details, and a second Right opens the next image. Up/Down selects detail elements; description and tag actions are reachable.
- Down from media or the final detail enters the bottom bar. Left/Right selects actions, Up returns to media.
- Fit static images completely in the detail pane and fullscreen view, preserving aspect ratio.
- In fullscreen, OK cycles fit, 2x, 3x, 4x, fit. While zoomed, arrows pan; Back restores fit before exiting fullscreen. Changing images resets zoom.
- Keep the original video controls and image decoder/pan boundaries.
- Update Simplified Chinese, Traditional Chinese, English and Japanese help and zoom instructions; retain system-following language selection and English fallback.
- Local translation tests and 602 generated-resource consistency checks passed. No on-device language validation was performed for this release.
- Add detail and zoom device regression scripts. Earlier functional checks passed on the Android 9 ZTE B863AV3.1-M2; this publication did not rerun device tests. GIF and all media formats are not comprehensively covered.

Version code: 180. Package: `se.zepiwolf.tws.tv`. Signed with the existing release key for in-place updates.

## beta-4.16.4-tv6 - 2026-10-10

- Redesign the overview as a centered image grid with two fixed sidebars; remove the legacy top search and bottom navigation bars.
- Put search, saved searches, filters and favourites on the left; page status, previous/next page, menu and help on the right.
- Replace overview swipe paging with focusable page buttons. Direction keys navigate the grid and sidebars without accidental paging.
- Restore the previous visible image when returning from a sidebar, and keep focus within the grid at its vertical limits.
- Unify neutral dark surfaces, icons, spacing and focus styles; protect image focus borders at viewport edges.
- Add device overview regression checks and update search/navigation checks for the new layout.
- Tested on the Android 9 ZTE B863AV3.1-M2 television box. Search keyboard, suggestions, submission and cancellation regression passed.

Version code: 178. Package: `se.zepiwolf.tws.tv`.
Update with the same signing key to preserve existing application data.
The adaptation remains based on The Wolf's Stash beta-4.16.4 by ZepiWolf; see README and NOTICE for attribution and limitations.
