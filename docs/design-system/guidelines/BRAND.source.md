# Caderly — Brand Concept

**Version:** 1.0
**Status:** Decided
**Author:** Hasan Kagalwala
**Date:** 2026-08-29
**Owner:** Caderly
**Supersedes:** Helyx brand concept (name retired — see §11)
**Companion documents:** `Caderly_PRD.md`, `UI_Guidelines.md`, `CLAUDE.md`

---

## 0. How to use this document

This is a decision record, not a mood board. Every rule below either has a reason
attached or a PRD clause it derives from. If a screen, string, or slide breaks one
of these rules, it is off-brand regardless of how it looks.

For implementers (human or agent): §5–§8 are the normative tokens. §9 is the
checklist. §3 is the one to read before writing any user-facing string.

---

## 1. Name and concept

**Caderly** — pronounced **KAD-ruh-lee**. Standardise on this and say it the same
way in every demo; the first customer who says "kad-RAY-lee" will otherwise decide
it for us.

The name is not decoration. It arrives with the concept attached, in three languages:

| Root | Meaning | Why it matters |
|---|---|---|
| *cadre* (French) | A **frame**. Also, in business French, the **managerial and professional staff** — *les cadres*. | The word means the structure and the people in it simultaneously. |
| *cadre* (English) | The trained core an organisation is built around. | Not headcount — the people the thing is built around. Exactly what an HRIS is a record of. |
| *quadrum* (Latin) | A square; ancestor of both. | Hands us the geometry of the mark before we draw a line of it. |

The `-ly` turns a common noun into a product and keeps us clear of the several
"Cadre" software companies that already exist.

### 1.1 The idea

> **A frame is what makes a thing hold its shape without being looked at.**

Every organisation already has a frame — thirty days of leave, Sat–Sun weekend,
your manager approves, no carry-over, IDs expire. It exists with or without
software. Usually it lives half in a handbook nobody has opened and half in the
head of one person in HR, which is why employees ask instead of knowing.

Caderly's job is to **make the frame visible and then get out of the way.** Not to
add process — to render the process the company already has, exactly, and show
every edge of it. That is a different promise from every other tool in the
category, which sell you *their* frame and ask you to adapt.

It also resolves cleanly onto the PRD: **§12 is the frame, §14 is the people.**
Configure the frame once per tenant; the people move through it.

### 1.2 Blurb (for decks, sites, design tools)

```
Caderly.

Policy clarity, without the gatekeeping.
Most HR software is built to manage people — score performance, track engagement,
route requests into a queue, and answer "why" with "contact your administrator."
Caderly shows the arithmetic. We believe a rule you can see is a rule you can trust.
The policy is still your company's — knowing it shouldn't be a favour.
```

---

## 2. Positioning

The wedge is already in the PRD's vision statement, and most vendors would have
edited it out: *"honest about the rules it enforces."*

HR software is notoriously opaque to the people subject to it. Balances appear
without explanation. Requests vanish into a queue. Errors say "contact your
administrator." Policy lives in a PDF nobody has opened since onboarding.

> **Positioning statement.** For small and mid-sized companies whose HR tool is
> either a spreadsheet or a suite they use 5% of, Caderly is an HRIS that enforces
> your policy exactly and explains every number it shows. Where other systems hide
> the rule and surface the result, Caderly shows the arithmetic — so employees stop
> asking HR, and HR stops doing arithmetic.

### 2.1 The three claims

| Claim | Backed by |
|---|---|
| **Fast** | `p95 < 400 ms` is not an NFR, it is a brand promise. Server-rendered, htmx-swapped, no spinner long enough to need a skeleton screen. |
| **Explicit** | Every number is shown with the rule that produced it. Every write is in the audit log (§18). Nothing happens to a person without a stated reason. |
| **Yours** | Your subdomain, logo, leave types, weekend, holidays, approval routing. Onboarding is one row and one invite. |

---

## 3. Voice

Not a tone of adjectives — a rule:

> **State the number, then the rule that produced it, in one sentence a person can
> act on.**

Never make someone open a policy document or email HR to understand what a screen
is telling them. Every example below derives from a business rule already in the PRD.

### 3.1 Worked examples

| Context | Say | Not |
|---|---|---|
| Balance (`BR-3`) | 12 of 30 days left. Resets 1 January — unused days don't carry over. | You have 12 days remaining. |
| Duration (`BR-7`) | 5 working days. Skips Sat–Sun and Eid al-Adha (Fri 7 Aug). | Duration: 5 days |
| Insufficient (`BR-4`) | 3 days requested, 2 available. Shorten the range to end Thu 13 Aug, or ask Aisha Rahman to adjust your balance. | Insufficient balance. |
| Routing (`§12.4`) | Sent to Rahul Menon. He'll get an email within a minute. | Your request has been submitted for approval. |
| Self-approval (`BR-6`) | You can't approve your own request. This one goes to Aisha Rahman instead. | Permission denied. |
| Overlap (`BR-15`) | You already have Vacation booked 3–5 Aug. Cancel that request first, or pick different dates. | Overlapping leave request detected. |
| Termination (`BR-11`) | Access ended today. 2 upcoming leave requests were cancelled. The record stays for 7 years. | Employee terminated successfully. |
| Expiry (`FR-9.2`) | Priya's Emirates ID expires in 30 days (14 Sep). She's been told too. | ⚠️ Document expiring soon! |
| Lockout (`FR-1.7`) | Too many attempts. Try again at 14:19, or reset your password now. | Account locked. Please try again later. |
| Unknown tenant (`FR-2.3`) | There's no Caderly workspace at flurb.caderly.app. Check the address, or ask whoever invited you for the link. | Unknown tenant. |

### 3.2 Always

- Name the person, not the role. "Sent to Rahul," not "sent to your manager."
- Give both numbers in any comparison. "3 requested, 2 available."
- Name the rule when it costs the user something.
- Offer the next action in the same sentence as the refusal.
- Pair relative dates with real ones. "in 30 days (14 Sep)."
- Buttons state the outcome. "Request 5 days," not "Submit."
- "Decline," not "Reject." Same DB value; different thing to read about a colleague at 8am.

### 3.3 Never

- "Oops," "Uh oh," "Something went wrong."
- Emoji in system messages. Birthday and work-anniversary mail (`§17.2`) are the two
  deliberate exceptions — those are from colleagues, not from the system.
- "Successfully." If it didn't fail, it succeeded.
- Exclamation marks in anything an admin action produces.
- "Contact your administrator" with no reason attached.
- Leaking whether a tenant slug or an email address exists.

### 3.4 Where this lives

All strings are externalised to `messages.properties` for i18n, so the voice is
enforceable in **one file**. Review it before Arabic translation starts —
arithmetic-first copy translates far more cleanly than idiom.

---

## 4. The mark

An **open square frame that is simultaneously a hard-edged C**, with a single
square sitting in the opening — someone entering, or on their way out. Both are HR.

### 4.1 Geometry — do not redraw by eye

```
Grid            64 × 64
Centreline      square 13 → 51, corner radius 8
Stroke          10, butt caps, round joins
Frame path      M40 13H21A8 8 0 0 0 13 21V43A8 8 0 0 0 21 51H48
Occupant        rect x=41 y=26 w=12 h=12 rx=2
Clear space     10 units (one stroke width) on all four sides
Minimum size    16 px (drop the occupant below 20 px)
```

### 4.2 The two details that carry the whole idea

**Soft outside, sharp inside.** A 10-unit stroke turning a centreline radius of 8
gives an **outer radius of 13 and an inner radius of 3**. The mark is welcoming from
the outside and precise on the inside — the product argument rendered as a geometry
rule rather than a tagline. Round the inner corners to match and it becomes any C in
any font.

**Unequal arms.** Top arm ends at `x=40`, bottom at `x=48`. Equal arms make a
symmetrical bracket that reads as punctuation; unequal arms give the mark direction
and stop the eye reading it as a borrowed typographic character.

**The frame is open** because a closed square is a box, and nobody wants to work at
a company that describes its staff as a box. The gap also makes the silhouette
asymmetric, which is what lets it survive at favicon size.

### 4.3 Never

Close the frame · mirror it to open leftward · centre the occupant inside the frame ·
round the inner corners · put it in a circle · add a second occupant · use it as a
bullet in body copy · animate it as a perpetual spinner · set it on a photograph.

### 4.4 Asset files

| File | Use |
|---|---|
| `caderly-mark.svg` | Primary mark, Petrol, with occupant. 20 px and up. |
| `caderly-mark-mono.svg` | Same geometry, `currentColor`. Inline in HTML, set `color:`. |
| `caderly-mark-small.svg` | Occupant removed. Below 20 px. |
| `caderly-favicon.svg` | Switches to `#6FBDD1` under `prefers-color-scheme: dark`. |
| `caderly-appicon.svg` | 512 px tile, white mark on Petrol. Export PNG at 512/192/180. |
| `caderly-lockup.svg` | Horizontal lockup. **Outline the text before distributing.** |

Prefer the CSS lockup on the web (inline mono SVG + a `<span>`) over the SVG lockup,
so the wordmark stays live text.

### 4.5 Animation

**One** sanctioned animation, on the **login page and marketing hero only**: the
frame traces itself over ~1 s, then the occupant fades in from the left as if
stepping into it. Nowhere else, and never on a route change — an app promising
`p95 < 400 ms` cannot spend a second on a logo between pages.

---

## 5. Colour

### 5.1 The constraint that decided it

Caderly is a status app. Approved, pending, rejected, expiring and cancelled appear
on nearly every screen and must mean the same thing everywhere. **Green, amber and
red are therefore already spoken for and cannot be the brand colour** — a
green-branded HRIS puts brand green next to approval green in every table row and
neither reads.

That eliminates most of the category's palettes and leaves four honest lanes:
near-black, blue, blue-cyan, violet. **Petrol** was chosen from the blue-cyan lane
(see §11 for what was rejected and why).

### 5.2 Tokens — light

| Token | Hex | Role |
|---|---|---|
| `--brand` | `#0F5568` | **Petrol.** Primary buttons, links, active nav, focus rings, the mark. Nothing else. |
| `--on-brand` | `#FFFFFF` | Text and icons on a Petrol fill. |
| `--brand-fill` | `#E3EFF2` | Tinted panels, selected chips, the arithmetic panel. |
| `--brand-line` | `#AFCFD7` | Borders on brand-tinted surfaces. |
| `--ink` | `#15171C` | All primary text. ~90% of the coloured pixels on screen. |
| `--ink-2` | `#4A5160` | Secondary text. Also **cancelled** and **archived** — neutral, because cancelled is not a failure. |
| `--ink-3` | `#5F6675` | Lightest permitted text. Captions, column headers. |
| `--mute` | `#6F7686` | Placeholders and disabled only. Never a data value. |
| `--paper` | `#F7F7F9` | Page ground. Faintly cool; never pure white. |
| `--card` | `#FFFFFF` | Tables, forms, panels. White-on-tinted signals "this is the record." |
| `--rule` | `#E3E4E9` | 1 px borders. Never text. |
| `--verify` | `#1F6B45` | Approved, active, valid, in date. Muted — approval is routine, not a celebration. |
| `--notice` | `#9A5F00` | Pending, expiring, policy consequence. "Here is a rule you should know." |
| `--halt` | `#A62A22` | Rejected, terminated, failed login. Genuinely destructive only; should be rare. |

### 5.3 Tokens — dark

Not an inversion, a re-grade. Cards **lift toward** the light rather than being holes
cut in black, which keeps table rows readable at density.

| Token | Hex |
|---|---|
| `--brand` | `#6FBDD1` |
| `--on-brand` | `#08181D` |
| `--brand-fill` | `#0F2229` |
| `--brand-line` | `#234550` |
| `--ink` | `#E7E9EE` |
| `--ink-2` | `#AEB5C2` |
| `--ink-3` | `#8B93A2` |
| `--mute` | `#6C7385` |
| `--paper` | `#0F1116` |
| `--card` | `#171A21` |
| `--rule` | `#262B35` |
| `--verify` | `#5CC08C` |
| `--notice` | `#E0A64A` |
| `--halt` | `#F0847C` |

### 5.4 Rules

1. **Brand colour appears only on:** primary buttons, links, active nav indicator,
   focus rings, and the mark. Everything else is neutral or semantic. That restraint
   is what keeps a data-dense screen readable.
2. **Semantic colours are never derived from the brand** and never overridden. A
   status means the same thing on every screen and in every theme.
3. **Rejected is Halt. Cancelled is Slate (`--ink-2`).** A rejection is a decision
   someone made about you; a cancellation is usually your own. Colouring them the
   same is the small dishonesty that makes HR software feel hostile.
4. **Status is never carried by colour alone** (`WCAG 1.4.1`). Every state gets a
   word as well as a hue — which also survives the black-and-white PDF export in
   `FR-10.4`, and the ~8% of male users with a colour vision deficiency.
5. **Petrol's one known cost:** at 43° it is the closest of the four candidates to
   approval-green. Keep them apart **by role** — brand in buttons/links/mark, green
   only in status chips with a word beside it. If it reads muddy once there are real
   screens, shift `--verify` to a warmer `#44762B` (5.07 : 1) and the gap opens to 93°.

### 5.5 Verified contrast (WCAG 2.1, computed)

| Pair | Light on `--paper` | Dark on `--paper` | Rating |
|---|---|---|---|
| `--ink` | 16.76 : 1 | 15.55 : 1 | AAA |
| `--ink-2` | 7.44 : 1 | 9.16 : 1 | AAA |
| `--ink-3` | 5.39 : 1 | 6.11 : 1 | AA |
| `--brand` | 7.79 : 1 | 8.88 : 1 | AAA |
| `--on-brand` on `--brand` | 8.33 : 1 | 8.52 : 1 | AAA |
| `--verify` | 6.04 : 1 | 8.43 : 1 | AA |
| `--notice` | 4.89 : 1 | 8.74 : 1 | AA (darkened from a true amber specifically to clear AA) |
| `--halt` | 6.60 : 1 | 7.46 : 1 | AA |
| `--rule` | 1.19 : 1 | — | Borders only. Never text. |

---

## 6. Typography

**IBM Plex — one superfamily, four jobs.** Same reasoning that produced a modular
monolith instead of six services.

| Face | Weights | Job |
|---|---|---|
| **Plex Sans** | 400 / 500 / 600 | Product UI and display. Headlines at −3.2% tracking. Every button, table header, nav item, form label. |
| **Plex Serif** | 300 Italic | One job only: the brand's sentences on the site, deck statement slides, the one paragraph of an invite email that isn't a link. **Never in the product.** |
| **Plex Mono** | 400 / 500 | Balances, durations, tenure, timestamps, employee codes, audit diffs, error codes. Anything a machine produced or a human compares down a column. |
| **Plex Sans Arabic** | 400 / 500 / 600 | Phase 2 RTL. Drawn to match — a font swap and `dir="rtl"`, not a re-selection. |

### 6.1 Why Plex and not Inter

- **Real tabular figures.** This product is balances, durations, dates and tenure in
  columns. Non-tabular numerals in a leave report look broken.
- **Arabic already exists**, by the same foundry, drawn to match.
- **Mono and Serif in the same superfamily**, so audit-log JSON and marketing prose
  come from the same hand.
- **Open licence, self-hostable.** No CDN round-trip on a page targeting
  `p95 < 400 ms`, and no third party in the CSP (`§19.6`).
- It doesn't look like Inter, which every other SMB SaaS reaches for.

### 6.2 Scale (px)

| Step | Size / Leading | Face | Where |
|---|---|---|---|
| Statement | 56 / 1.04 | Sans 600 | Deck covers, marketing hero. |
| Page title | 28 / 1.2 | Sans 600 | "People (8)", "Team Calendar". |
| Section | 19 / 1.3 | Sans 600 | Card headers, profile tab headings. |
| Body / UI | 15 / 1.55 | Sans 400 | Forms, prose, list rows. |
| Table | 14 / 1.45 | Sans 400 | Dense grids. Numerics in Mono, tabular. |
| Label | 11 / 1.4 | Mono 500 | Uppercase, +10% tracking. Column heads, metadata, statuses. |

---

## 7. Motion and density

Most brand guidelines specify motion at 250–400 ms because it reads as "premium."
At that duration a 180 ms htmx swap arrives and then waits for its own animation —
spending the performance budget on decoration. Caderly goes the other way, and the
restraint **is** the personality.

| Duration | Easing | Use |
|---|---|---|
| 120 ms | `ease-out` | Hovers, focus rings, tooltips, chip selection. |
| 180 ms | `cubic-bezier(.3, 0, .2, 1)` | htmx swaps, slide-overs, modal entry, accordions. |
| — | — | **Never:** page transitions, skeleton screens, spinners under 400 ms, staggered list entrances, the logo animating on route change. |

**Density.** An HRIS is a reference tool, not a reading experience. Table rows at
40 px, 8 px base spacing unit, sidebar at 240 px. Resist adding whitespace to make it
feel calm — for an HR admin reconciling 50 balances, calm *is* seeing 25 rows without
scrolling.

---

## 8. Surfaces

The PRD makes a native mobile app an explicit non-goal and puts the marketing site in
Phase 4, so the surfaces to design now are not the ones a generic brand project
would assume.

### 8.1 Product shell

- Mark plus wordmark top-left. Tenant logo, where uploaded, sits to the right of a
  hairline divider — **guest, not host**.
- Sidebar 240 px. Active item marked by a 2 px brand left edge **and** a weight
  change, so it still reads without colour.
- Text fallback of the tenant name in Plex Sans 600 when `logo_url` is null. This
  fallback will be used more than the logo for a long time — design it properly.

### 8.2 Transactional email

The hardest surface: it lands in an inbox with no context.

- Subject line names the **tenant**, not Caderly: `"Priya Nair requested Vacation — 3 days"`.
- Single column, 600 px, **system-font stack**. Never web-font an email.
- Body in the plain-arithmetic voice: the request, the dates, the working-day count,
  one button.
- Every send needs a `text/plain` part in the same voice. Arithmetic-first copy
  degrades to plain text perfectly — another argument for §3.
- Footer: "Sent by {tenant} via Caderly." The only Caderly branding, and it doubles
  as the distribution channel.

### 8.3 Super Admin console

- Dark theme by default so it looks like a **different application**. That visual
  break is a genuine safety control when a Super Admin can impersonate a tenant Admin
  (`FR-12.5`).
- Impersonation shows a persistent brand band across the top: *"You are impersonating
  Aisha Rahman at Acme. Reason: support ticket #412."* Named person, stated reason,
  always visible.

### 8.4 Login, site, deck

- The login page and the unknown-tenant 404 are the two in-app moments that are purely
  Caderly. Mark, wordmark, Petrol, the trace animation.
- The login page names the company (`FR-2.2` resolves the tenant before render) and
  shows the subdomain beneath it. Proves the user is where they meant to be and
  quietly kills a class of phishing.
- Lead the deck and the site with a real screenshot of the booking modal reading
  *"5 working days. Skips Sat–Sun and Eid al-Adha."* The differentiator is a
  sentence, and it demos in four seconds.
- **Do not build a marketing site before Phase 4.** One page with the mark, that
  sentence, one screenshot and a mail link out-converts a full site nobody has time
  to maintain.

---

## 9. Implementation checklist

### 9.1 CSS custom properties

```css
:root {
  /* brand */
  --brand:        #0F5568;
  --on-brand:     #FFFFFF;
  --brand-fill:   #E3EFF2;
  --brand-line:   #AFCFD7;
  /* neutrals */
  --ink:          #15171C;
  --ink-2:        #4A5160;
  --ink-3:        #5F6675;
  --mute:         #6F7686;
  --paper:        #F7F7F9;
  --card:         #FFFFFF;
  --rule:         #E3E4E9;
  /* semantic — never derived from --brand, never overridden */
  --verify:       #1F6B45;
  --verify-fill:  #E4F0EA;
  --notice:       #9A5F00;
  --notice-fill:  #F8EFDF;
  --halt:         #A62A22;
  --halt-fill:    #F8E9E7;
  /* type */
  --sans:  "IBM Plex Sans", -apple-system, "Segoe UI", Helvetica, Arial, sans-serif;
  --serif: "IBM Plex Serif", Georgia, serif;
  --mono:  "IBM Plex Mono", ui-monospace, "SF Mono", Menlo, monospace;
}

@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --brand:#6FBDD1; --on-brand:#08181D; --brand-fill:#0F2229; --brand-line:#234550;
    --ink:#E7E9EE; --ink-2:#AEB5C2; --ink-3:#8B93A2; --mute:#6C7385;
    --paper:#0F1116; --card:#171A21; --rule:#262B35;
    --verify:#5CC08C; --verify-fill:#12241B;
    --notice:#E0A64A; --notice-fill:#241D10;
    --halt:#F0847C;   --halt-fill:#2A1613;
  }
}
/* repeat the same block under :root[data-theme="dark"] so an explicit
   toggle wins in both directions */
```

### 9.2 Bootstrap 5 bridge

```css
:root {
  --bs-primary:            #0F5568;
  --bs-body-color:         var(--ink);
  --bs-body-bg:            var(--paper);
  --bs-border-color:       var(--rule);
  --bs-body-font-family:   var(--sans);
  --bs-body-font-size:     .96rem;
}
.table td { font-variant-numeric: tabular-nums; }
```

### 9.3 Code changes this brand implies

| # | Change | Why |
|---|---|---|
| 1 | **Drop `primary_color` from the `tenant` table.** | One colour for everyone now. It becomes an application constant, not a column plus a form field plus a per-tenant email merge. The DDL default `#4f46e5` is Tailwind indigo-600 and the comment still points at superseded guidelines. |
| 2 | **Return the duration *breakdown*, not just the number.** `computeDuration` (`§12.3`) already walks every day and decides to skip it, then discards that. Have it return the skipped days with their reasons. | A few lines, and it is what makes *"5 working days · skips Sat–Sun and Eid al-Adha (Fri 7 Aug)"* possible. That sentence is the entire differentiator. |
| 3 | **Rewrite `messages.properties` in the §3 voice** while it is still short. | Highest-leverage brand artefact in the repo. One file. It is what employees actually experience. |
| 4 | **Add a computed on-colour helper** if tenant colour ever returns. | Not needed today; noted so nobody reintroduces coloured buttons without an accessible pairing. |
| 5 | **Order Class 9 + 42 trademark clearance for "Caderly"** before `caderly.app` appears in a production email link. | `slug` is immutable by design (`BR-13`) and the domain is baked into invite links and bookmarks. A domain change later is a migration, not a rebrand. |

### 9.4 Build order

1. Tokens (§9.1) + Bootstrap bridge (§9.2) — half an hour, moves every existing page at once.
2. Booking modal — the demo screen. Forces change #2 above.
3. For Action inbox.
4. Login page.
5. Profile.

---

## 10. Reference screens

The booking modal is designed against `AC-LV.2` exactly: tenant weekend Sat/Sun,
public holiday Fri 2026-08-07, booking Mon 3 Aug → Mon 10 Aug 2026, expected
**5 working days**. August 2026 does begin on a Saturday, so the day strip is
correct rather than plausible — **build the modal against this case and the
screenshot doubles as a passing test.**

Key elements, in priority order:

1. **The arithmetic panel.** The number in Plex Mono at 24 px in Petrol; the only
   place in the modal the brand colour appears besides the submit button.
2. **The day strip.** Eight cells, four filled and four dashed, with the reason
   named underneath. A user who disagrees with the number can see where it came from
   — the difference between trusting the system and emailing HR about it.
3. **"18 → 13 of 30" before they commit.** Nobody should have to submit a request to
   find out what it costs them.
4. **A named approver.** "Goes to Rahul Menon" — `§12.4` already knows who it is.
5. **A button that states the outcome.** "Request 5 days."

---

## 11. Decision log

Kept so nobody relitigates these in six months.

### 11.1 Name — Helyx, retired

`HELYX` is the name of ENGYS's computational fluid dynamics suite, an actively
released enterprise product with an open-source GUI on GitHub, and there is also a
UK geospatial consultancy trading as Helyx. Both sit in or adjacent to **Nice Class 9
and 42** — the same classes an HRIS registers in. Real market confusion was unlikely,
but an examiner looks at class before market. Retired in favour of Caderly, which
surfaced no software company using the name; the nearest marks are bare *Cadre*
(warehouse management, unrelated classes).

The concept was rebuilt from scratch — the helix idea (cycle plus progression) did
not survive the rename, and the *cadre* concept is a better fit anyway.

### 11.2 Colour — three lanes rejected

| Option | Hex | Why not |
|---|---|---|
| **A · Cadre Ink** | `#171B22` | The most rigorous answer: brand colour is structure, zero semantic conflict, cheapest to execute solo. Rejected because near-monochrome brands are hard to recall, and a solo-built product with no marketing budget needs a customer to remember which screenshot was ours. |
| **B · Cobalt** | `#1B4FA8` | The safe answer, and nobody gets fired for blue. Rejected as the most crowded lane in HR — Deel, Personio, Factorial. |
| **D · Iris** | `#4634B8` | Where `#4f46e5` was pointing, done properly: deeper, cooler, furthest from every semantic hue. Rejected as closest to HiBob's territory, and violet dates fastest of any colour in software. |

**C · Petrol `#0F5568` chosen** — carries blue's credibility, nobody in SMB HR owns
it, holds up in a Gulf market where navy enterprise software is wallpaper. Known cost
documented at §5.4.5.

### 11.3 Mark — three directions rejected

| Direction | Why not |
|---|---|
| **Four squares** (from *quadrum*, one cell filled) | Also the universal icon for app launcher, dashboard and grid view. Ours would be one of forty on a customer's screen. |
| **Corner brackets** | Elegant and empty. Reads as a crop tool or screenshot utility, and the centre — where the eye lands — carries nothing. A frame with nobody in it is the opposite of the pitch. |
| **Nested frames** ("an organisation inside an organisation") | True, but it is the shape of half the fintech card icons, and the two concentric outlines blur into one another below 24 px. |

### 11.4 Type — Inter rejected

The default every other SMB SaaS reaches for, with no Arabic companion drawn to
match and no mono/serif siblings. See §6.1.

---

*Contrast ratios computed to WCAG 2.1. Dates verified against the 2026 calendar.
Name collisions checked August 2026. IBM Plex is open-licensed and self-hostable.*
