# Sotohp — User Stories

> _Last checked: 2026-06-11 — verified against frontend commit `81c5993` (Add
> support for `BirthName`). All frontend changes up to this commit are reflected
> below._

This document captures every end-user feature exposed through the **Sotohp** web
user interface (`frontend-user-interface/`). It was reverse-engineered from the
running single-page app and is organised by functional area. Each story is
written from the user's perspective and followed by the concrete behaviours that
implement it.

Sotohp is a self-hosted personal photo & video library. The UI is a tabbed
single-page application backed by a REST/NDJSON API. The main areas are:
**Viewer, Mosaic, Bags, Portfolios, Map, Persons, Owners, Stores, Settings.**

> **Data model (add-only):** A **store** is a user storage place (a root
> directory). Each store contains many **bags** (mapped one-to-one to the
> directories inside it), and each bag contains many **originals**. A media
> always belongs to **exactly one** bag. Stores, bags, and originals are
> *append-only*: they are discovered and maintained by the synchronization
> process and can be **added but never removed or deleted** through the UI or
> API. Users can edit descriptive attributes (e.g. a bag's name, location,
> cover, keywords) but cannot delete a store, a bag, or a photo.

> **Note:** The frontend runs **with or without authentication.** The app reads
> the server's `/api/system/config` at startup and adapts automatically: when
> auth is enabled it requires a Keycloak sign-in; when auth is disabled there is
> no login or session at all (the Logout button is hidden and images load
> directly). All other features behave identically in both modes.

---

## 1. Authentication & Session

- **As a user, I can sign in to protect access to my library.**
  - On startup the app reads `/api/system/config`; if authentication is enabled
    it redirects me through a Keycloak login (silent `check-sso`, then full login
    if needed).
  - If a login response cannot be validated, I'm shown a clear "Authentication
    Failed" screen with a button to return to the app.
  - If the authentication server is unreachable, I see an "Initialization Error"
    screen with a Retry button.

- **As a user, I can sign out** using the **Logout** button in the top-right of
  the header. The button is hidden entirely when authentication is disabled.

- **As a user, my images load securely without leaking my token.**
  - A service worker injects my bearer token into image/thumbnail requests so the
    token never appears in URLs.
  - On insecure origins (plain HTTP on a LAN IP) where a service worker can't
    run, the app falls back to a `?token=` query parameter so images still load.
  - When authentication is disabled, the service worker is removed so images load
    directly with no delay.

---

## 2. Global Navigation

- **As a user, I can switch between feature areas** using the header tabs:
  Viewer, Mosaic, Bags, Portfolios, Map, Persons, Owners, Stores, Settings.

- **As a user, I can switch tabs from the keyboard.** `Alt+Page Down` moves to
  the next tab and `Alt+Page Up` to the previous one, cycling around the ends.
  The shortcut works from any tab and is ignored while typing in a form field.

- **As a user, I can deep-link / bookmark a tab.** The active tab is reflected in
  the URL hash (e.g. `#mosaic`), and reopening that URL restores the tab. The
  legacy `#world` hash maps to the Map tab.

- **As a user, I get non-blocking feedback** for every action via toast
  notifications (success / error / warning / info).

- **As a user, I can drive dialogs from the keyboard.** In any modal, **Esc**
  closes it and **Ctrl/Cmd+Enter** triggers the primary (Save/Create) action.

---

## 3. Image Viewer

The Viewer shows one media at a time with a large image area and an info sidebar.

### Browsing & navigation
- **As a user, I can move through my library** with First, Previous, Next, Last,
  and Random buttons.
- **As a user, I can navigate from the keyboard** while the Viewer is active:
  - `Home` / `End` → first / last
  - `PageDown` / `PageUp` → next / previous
  - `Space` → play / pause slideshow
  - `+` / `-` → zoom in / out, `0` → reset zoom
  - Arrow keys → pan the photo when zoomed in (see below)
- **As a user, my last viewed photo is restored** when I reopen the app (falling
  back to a random photo if it can no longer be loaded).

### Zoom & pan
- **As a user, I can zoom into a photo** with zoom-in / zoom-out buttons, the
  mouse wheel (zooming toward the cursor), or keyboard shortcuts; the current
  zoom level is shown as a percentage and clicking it resets to 100%.
- **As a user, I can pan** a zoomed photo by dragging it, or with the **arrow
  keys** (←/→/↑/↓ move the view in that direction). Arrow-key panning only acts
  while zoomed in and has no effect at fit scale. Panning is clamped so the image
  can't be dragged completely out of view.
- The viewer automatically upgrades to the full-resolution original when I zoom
  in or go fullscreen, and reverts to the normalized variant otherwise.

### Display & fullscreen
- **As a user, I can view a photo fullscreen** with a dedicated button; an
  overlay shows the bag name, starred state, and a location pin.
- **As a user, I can rotate a photo left or right** (90° steps). The orientation
  override is saved and reused everywhere the media is shown (mosaic, portfolios,
  etc.).

### Slideshow
- **As a user, I can run a slideshow** with Play/Pause.
  - I can choose the delay between slides (10 / 20 / 30 seconds).
  - I can choose the order: sequential ("next") or random.
  - Slides get a gentle Ken-Burns zoom animation timed to the slide delay.
  - My duration and mode choices are remembered across sessions.

### Starring
- **As a user, I can star / unstar the current photo** with one click; the change
  is optimistic and reflected immediately in the sidebar and fullscreen overlay.

### Information sidebar
- **As a user, I can see metadata about the current photo:**
  - **Date** — clicking it jumps to the Mosaic positioned at that moment.
  - **Description**
  - **Bag** — clicking it jumps to that bag in the Bags tab.
  - **Location** — a colour-coded pin (green = GPS known, orange = estimated/
    user-defined, red = none). When a true GPS location is known, clicking it
    shows the photo on the Map.
  - **Owner** — resolved from the photo's store → owner.
  - **Camera** — aperture, exposure time, ISO, focal length, camera name.
  - **Keywords** — shown as chips.

### Editing media
- **As a user, I can edit the current photo** via a modal:
  - Edit **description**, **shoot date/time**, and **keywords** (chip editor).
  - Set a **user-defined location** on an interactive map: click or drag a
    marker, copy from the photo's own GPS location, remember the current
    selection, reuse the last remembered selection, or reset the location.
- **As a user, from the edit modal I can:**
  - Set this photo as the **cover for its bag**.
  - Set this photo as the **cover for its owner**.
  - **Add this photo to a portfolio**.

### Faces
- **As a user, I can toggle a faces overlay** on the photo; the setting is
  remembered.
- **As a user, I can see who is in a photo:** confirmed faces show a name chip;
  faces with an AI-inferred (but unconfirmed) identity show a "Name?" chip.
- **As a user, I can confirm an inferred face** by clicking its chip, or confirm
  **all** inferred faces on the photo at once with a "Confirm all" button.
- **As a user, I can edit a face's identification** (per-face edit button) — see
  the Face identification modal in §8.
- **As a user, I can add a missing face** by clicking "Add face" and dragging a
  box on the photo; the box is captured in the image's natural coordinates even
  if the photo is rotated. Esc cancels.
- **As a user, I can delete a face** from the face edit modal.

### Other per-photo actions
- **As a user, I can download the original file** with a button; the filename is
  derived from the shoot date, and videos download with an `.mp4` extension.

---

## 4. Mosaic (timeline grid)

- **As a user, I can browse my whole library as an infinite-scrolling grid**,
  newest first, with photos streamed in as I scroll up (newer) or down (older).
- **As a user, I can change the thumbnail size** (Small / Medium / Large); my
  choice is remembered.
- **As a user, I can jump to any point in time** using the vertical timeline on
  the left:
  - Year markers are placed on a logarithmic scale.
  - Clicking the timeline reloads the grid centred on that date.
  - Hovering shows a date tooltip; a cursor indicator tracks my scroll position.
- **As a user, I can open any tile in the Viewer** by clicking it.
- **As a user, I get a hover tooltip** on each tile showing the bag name and
  date; thumbnails lazily upgrade to a higher-resolution image on hover.
- **As a user, I can download a tile's original image** via a per-tile button.
- My last selected timeline position is remembered across reloads.

---

## 5. Map

- **As a user, I can see all my located media on a world map** (Leaflet) with
  marker clustering. Media are included when they have a GPS, user-defined, or
  deducted location.
- **As a user, I can refresh the map** to pick up newly located media.
- **As a user, I can inspect a marker:** its popup shows the bag name, starred
  state, date, and a thumbnail, plus an **Open** button that loads the photo in
  the Viewer.
- **As a user, I'm shown loading progress** (marker count) while data streams in.
- **As a user, I can jump from the Viewer to a photo's exact location** on the
  map (from the clickable location pin), which centres the map and opens that
  marker's popup.

---

## 6. Bags

A **bag** is a directory-backed grouping of medias. Bags are created
automatically during synchronization (one per media directory under a store) and
are never deleted; the UI exposes browsing and editing of descriptive attributes
only — there is no manual create or delete.

- **As a user, I can see all my bags** as a list (newest first) with a preview
  thumbnail, name, timestamp, and a location pin when the bag has a location.
- **As a user, I can refresh the list.**
- **As a user, I can open a bag in the Mosaic** at its timestamp by clicking
  its tile.
- **As a user, I can edit a bag:**
  - Name, description, timestamp, a **"Published On" URL** (validated), and
    keywords.
  - An interactive **location** map (place/drag marker, remember selection, reuse
    last selection, reset).
- **As a user, I can set a bag's cover photo** from the media edit modal (§3).
- **As a user, my scroll position is preserved** when I leave and return to the
  Bags tab, and I can scroll the list with the keyboard (Home/End/PageUp/
  PageDown/arrows).
- **As a user, I can be deep-linked to a specific bag** (e.g. from the Viewer),
  which scrolls to and briefly highlights it.

---

## 7. Portfolios

Portfolios are curated collections of assets (optionally cropped) drawn from the
library.

- **As a user, I can see all my portfolios** as cards, each showing a composite
  preview (up to 4 assets), the asset count, name, and description.
- **As a user, I can refresh** the list and **create a portfolio** (name,
  optional description).
- **As a user, I can open a portfolio** to see its detail view: title,
  description, and the list of assets.
- **As a user, I can manage a portfolio** from its header: **View assets**
  (fullscreen viewer), **Edit** (name/description), and **Delete** (with
  confirmation).
- **As a user, in the asset list I can, per asset:**
  - See the thumbnail with its **crop region applied** and a "✂ Cropped" badge
    when cropped, plus the asset's description.
  - **Open in viewer tab** (loads the full media in the main Viewer).
  - **Edit** the asset's description and crop region.
  - **Remove** the asset from the portfolio (with confirmation).
- **As a user, I can edit an asset's crop:**
  - Draw a new crop box by dragging on the image.
  - Move the box or resize it from any corner handle.
  - Remove the crop to use the full image.
  - Live coordinates/status are displayed.
- **As a user, I can browse a portfolio fullscreen:**
  - Previous / Next / Close controls and keyboard arrows / Esc.
  - The original image is shown with the crop and any rotation applied.
  - An info line shows position (e.g. "3 / 12"), crop indicator, and description.
- **As a user, I can add a photo to a portfolio** from the Viewer or media edit
  modal: a picker lists existing portfolios (with asset counts), lets me attach
  an optional per-asset description, and lets me create a new portfolio inline.

---

## 8. Persons & Faces

- **As a user, I can see all known persons** as a list sorted by last then first
  name, each with their chosen face thumbnail, birth date, and description.
- **As a user, I can quickly filter persons** by name (including birth name) or
  description; the filter is remembered for the session and can be cleared with a
  reset button.
- **As a user, I can refresh the list** and **create a person** (first name, last
  name, birth name, birthdate, email, description).
- **As a user, I can edit a person** (same fields) — the edit modal also previews
  their chosen face.

### A person's faces
- **As a user, I can open a person to see all faces identified as them**, shown as
  a grid with adjustable face size (Small / Medium / Maximum).
- **As a user, I can hover a face** to preview the source photo (and inferred
  name/confidence when relevant) in a tooltip, and **click a face to open it in
  the Viewer**.
- **As a user, I can validate AI suggestions for a person:**
  - Toggle into **validation mode**, which shows faces *inferred* to be this
    person but not yet confirmed (with a pending count).
  - Confirm faces individually, **Confirm all**, or **Confirm selected**.
  - Multi-select faces by clicking, Shift-click for a range, or click-and-drag
    across tiles.
- **As a user, I can edit or delete a person** from the person-faces header
  (delete asks for confirmation).

### All inferred faces (library-wide triage)
- **As a user, I can review every unconfirmed inferred face across the whole
  library** via "All inferred faces".
  - Adjustable face size.
  - Filter by person name / description.
  - Sort by Person+Date, Person+Confidence, or Confidence.
  - **Confirm all** shown faces or **Confirm selected**.
  - Each tile shows the inferred person's name and confidence, with hover preview
    of the source photo.

### Identifying a single face
- **As a user, I can identify a face** via the face edit modal (reachable from the
  Viewer overlay and the face grids):
  - A **typeahead combobox** to search and pick a person (keyboard navigable).
  - **Recent persons** quick-pick buttons (my last 10 choices, stored locally).
  - **Save** to set/update the identification.
  - **Remove** to clear an existing identification.
  - **Use as chosen face** to set this face as the person's representative
    thumbnail.
  - **Delete face** to remove the face entirely.

---

## 9. Owners

- **As a user, I can see all owners** as a list with a thumbnail (from the owner's
  cover photo), name, and birth date.
- **As a user, I can refresh the list**, **create an owner** (first name, last
  name, birthdate), and **edit an owner**.
- **As a user, I can set an owner's cover photo** from the media edit modal (§3).

---

## 10. Stores

Stores are the on-disk photo source directories that feed the library.

- **As a user, I can see all stores** with their name + base directory, owner
  name, and a thumbnail.
- **As a user, I can refresh the list** and **create a store:**
  - Name, **base directory** (required), an **owner** chosen via name typeahead,
    and optional **include** / **ignore** file masks.
- **As a user, I can edit a store's** name, base directory, include mask, and
  ignore mask.

---

## 11. Settings — Synchronization

- **As a user, I can keep my library up to date** from the Settings tab.
  - **Quick scan:** only index files added in the last *N* days (with suggested
    presets 7 / 20 / 42).
  - **Full sync:** scan all stores. The Synchronize button label reflects the
    chosen mode (quick / full).
  - My quick-scan toggle and day count are remembered.
- **As a user, I can monitor a running synchronization:**
  - Live status shows whether it's running, the elapsed duration, the
    processed / checked item counts, and the last-update time.
  - The status polls automatically while the tab is open and stops when I leave.
  - The button is disabled while a sync is running.
  - When a run finishes, cached lookups are invalidated so newly indexed media
    appear without a manual reload.

---

## 12. Cross-cutting behaviours

- **Lazy, throttled loading:** thumbnails across Bags, Persons, Owners, Stores,
  Portfolios, and the Mosaic load on demand (IntersectionObserver + concurrency
  limits) for responsiveness on large libraries.
- **Streaming lists:** large collections (media, bags, persons, faces, owners,
  stores, portfolios) arrive over NDJSON streams and render incrementally.
- **Optimistic updates with rollback:** star toggle and rotation update the UI
  immediately and revert on failure with an error toast.
- **Discoverable shortcuts:** controls that have a keyboard shortcut show it in
  their hover tooltip (e.g. Next "Next (Page Down)", Play "Play/Pause (Space)",
  tabs "Alt+Page Down / Alt+Page Up", zoom-reset notes arrow-key panning).
- **State persistence (localStorage / sessionStorage):** last viewed media,
  faces-overlay toggle, slideshow settings, last-remembered location, mosaic size
  & timeline position, bags scroll position, persons filter, and face-grid size
  are all remembered.
