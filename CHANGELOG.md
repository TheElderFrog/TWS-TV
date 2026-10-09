# Changelog

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
