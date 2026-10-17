This UI/UX review and roadmap for **Tvivo** focuses on elevating the current "functional but fragmented" state to a premium, WinUI-inspired flagship experience.

---

### 1. Candid UI/UX Critique

#### **Overall System Issues (High Severity)**
*   **Focus Ambiguity:** In Screen `01`, the coral box on "Movies" could be the selection *or* the focus. TV users need absolute certainty: "Selection" is a state; "Focus" is a spotlight.
*   **Density & Waste:** The 130px top-bar dead space and massive grid gaps (Screen `23`) make the app feel "empty." A 5-column movie grid on a 1080p screen is inefficient for browsing thousands of titles.
*   **Component Clipping:** "Categ" (Drawer) and "Sort" (Focus frame) indicate layout bounds are not accounting for focus scaling or intrinsic text size.

#### **Screen-Specific Critique**

| Screen | Issues | Severity |
| :--- | :--- | :--- |
| **01/43 (Landing/Home)** | Vertical "Categ" label is illegible. Refresh button is "floating" without context. Header typography is flat. | **High** |
| **11 (Drawer)** | Count text (48936) is too small for 10ft. "Filter" field lacks a clear focused/active state. | **Med** |
| **23/36 (Grid/Sort)** | "Sort" as bare text is not a D-pad target; it must be a button. 5 columns cause excessive scrolling. | **High** |
| **32 (Detail)** | The "4K" title bug (parsing failure) destroys trust. Action bars are too wide, making "Play" feel like a giant slab. | **High** |
| **52 (Lower Home)** | Live TV horizontal cards lack a progress bar (EPG context). "See all" has failing contrast ratios. | **Med** |
| **42 (Login)** | Keyboard occlusion of the "Sign in" button is a "blocking" UX failure for remote users. | **High** |

---

### 2. Target Design: The Tvivo Design System

#### **Layout & Spacing**
*   **The 24dp Rule:** All gutters and margins set to 24dp (TV) or 16dp (Mobile).
*   **Safe Zones:** 48dp horizontal, 27dp vertical (overscan protection).
*   **Grid Density:** 
    *   **Movies (2:3):** 7 columns on TV. 
    *   **Live/Series (16:9):** 4 columns.
*   **Shelf Rhythm:** Section headers at `Subtitle1` (20sp). Row spacing: 32dp.

#### **Typography (Based on Inter or Montserrat)**
*   **Headline:** 36sp, Semi-bold (Section Titles).
*   **Subhead:** 20sp, Medium (Category/Row Titles).
*   **Body:** 16sp, Regular (Synopsis).
*   **Micro:** 12sp, Bold (Badges/Pills).

#### **Focus & Selection System**
*   **The Spotlight:** Focused items scale **1.08x** and gain a **3dp Coral Border** with a soft outer glow.
*   **Selection:** The active Tab (e.g., "Movies") gets a **4dp Coral Underline** (WinUI style), *not* a box. This separates "Where I am" from "Where my cursor is."

#### **Top Bar & Navigation**
*   **Height:** Reduce to 80dp.
*   **Layout:** [Logo] [Tabs: My Tvivo | Movies | Series | Live TV] ... [Search Icon] [Settings Icon].
*   **Breadcrumbs:** Category grids show `All Categories > Action` in a 14sp light grey font above the main title.

#### **Detail Page Refactoring**
*   **Layout:** Two-column split. Left (30%): Large poster + Metadata pills (Year, Rating, 4K, Duration). Right (70%): Title, Synopsis, Action Buttons (Max width 320dp).
*   **Pills:** Use 12dp rounded corners with a semi-transparent white background.

#### **Poster Fallbacks**
*   **Design:** Replace "A" squares with a gradient-textured placeholder containing a centered movie-reel icon and the full title in the bottom-third for context.

---

### 3. Sliced Implementation Plan

#### **Slice 1: Navigation & Focus Foundation**
*   **Objective:** Fix "Where am I?" and "What is focused?"
*   **Tasks:** 
    *   Implement 1.08x scale + 3dp border focus system globally.
    *   Redesign Top Bar: Underline for selection, border for D-pad focus.
    *   Fix Login: Scrollable container so "Sign in" stays above the keyboard.
*   **Acceptance:** User can distinguish focused vs. selected tab at 10ft; "Sign in" visible while typing.

#### **Slice 2: The High-Density Grid & Drawer**
*   **Objective:** Efficient catalog browsing.
*   **Tasks:** 
    *   Expand Movies grid to 7 columns.
    *   Turn "Sort" and "Filter" into clearly defined buttons (48dp height).
    *   Fix Drawer: Replace vertical text with a "hamburger" icon that expands to a full label.
*   **Acceptance:** "Sort" shows coral focus frame without clipping; grid shows 7 posters per row.

#### **Slice 3: Card Intelligence & Badges**
*   **Objective:** Fix visual collisions and metadata errors.
*   **Tasks:** 
    *   Move Quality Badges (4K/HD) to bottom-left, overlapping the poster edge but avoiding provider logos (top-right).
    *   Fix Title Parsing: "4K 1992" -> Title: "1992", Badge: "4K".
    *   Standardize Live TV cards with EPG progress bars.
*   **Acceptance:** Badge does not touch provider logo; titles show actual name, not quality tags.

#### **Slice 4: The Premium Detail Page**
*   **Objective:** Cinematic presentation.
*   **Tasks:** 
    *   Implement the two-column detail layout.
    *   Add Year/Quality/Duration pills.
    *   Restrict action button width to 320dp.
*   **Acceptance:** Detail page looks balanced; synopsis is readable without taking 100% width.

#### **Slice 5: Polish & Fallbacks**
*   **Objective:** Remove "Broken" feel.
*   **Tasks:** 
    *   Implement Gradient+Icon+Title poster fallbacks.
    *   Adjust contrast on "See all" and hint text (aim for 4.5:1).
    *   Apply subtle background gradient behind text in grids for readability.
*   **Acceptance:** No more "Letter squares"; all text passes accessibility contrast checks.

---

### 4. Risks and Open Questions

*   **Risk: D-pad Dead Ends.** In the Drawer, if "Filter" is empty, does the D-pad get stuck? 
    *   *Mitigation:* Always ensure at least one "ALL" category is focusable.
*   **Risk: Performance.** 7-column grids with focus scaling can lag on low-end Android boxes (FireStick Gen 1/2). 
    *   *Question:* Should we offer a "Low Performance" mode that disables focus scaling in Settings?
*   **Open Question: Mobile/Tablet.** How does the "Categories" drawer behave on touch? 
    *   *Proposal:* On touch, use a standard side-swipe gesture; on TV, use the Hamburger click.
*   **Open Question: Metadata.** Does the Xtream API consistently provide "Year" and "Duration" for all providers? 
    *   *Plan:* Build a "null-safe" pill layout that collapses if data is missing.
