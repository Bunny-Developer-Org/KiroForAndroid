# Visual language: steer toward Kiro Crew

- **Status:** Proposed direction. Not an ADR — this constrains *look*, not architecture.
- **Date:** 2026-09
- **Applies to:** every screen in `app/ui/**`, and the notification surfaces in `app/service/`
- **Depends on:** [ADR-002](adr/ADR-002-react-native-vs-native.md) (Compose is the renderer), [ADR-003](adr/ADR-003-tech-stack.md) (`ui/theme/` package, Material 3)

The point of this document is that ten people building ten screens in parallel produce **one app**. ADR-003 pins the stack so the code merges; this pins the surface so the screens look like they were designed together. Where it gives a number, use that number rather than a nearby one.

---

## 1. The steer

**Kiro Crew is the reference. Not Kiro IDE, not Kiro Web.**

Stated plainly about method: the Crew characterisation below is derived from reading [`kirodotdev/KiroCrew`](https://github.com/kirodotdev/KiroCrew) — its token file (`website/src/index.css`), its Tailwind theme (`website/tailwind.config.js`), and its two frontend contracts (`website/docs/theming-contract.md`, `website/docs/page-layout.md`). **The contrast with IDE and Web is the project's steer, taken as given — it is not a comparison this document made.** So what follows defines Crew's look positively and does not claim anything about what the other two surfaces do.

One consequence worth surfacing now, because it is already written down elsewhere: [ADR-002 §5](adr/ADR-002-react-native-vs-native.md) lists *"transcript rendering must match Kiro Web pixel-for-pixel"* as a condition that would flip the app to React Native. **This document is a decision not to do that.** Following Crew rather than Web keeps that flip condition unmet, which is a second, independent reason the native recommendation holds. If someone later argues for Web parity, they are re-opening ADR-002, not just changing colours.

### What "the Crew look" actually is

Seven properties, each of which is a decision you can get wrong:

1. **Dark-first, and the dark is blue-black, not grey.** `#12141a` — a near-black with a measurable blue cast. The light theme exists and is complete, but the design was drawn in the dark and the light theme is its inversion, not the other way round.
2. **Separation is done with hairlines, not shadows.** A 1px `--border` at `#27272a` divides surfaces. Shadows exist but are small and are mostly for genuinely floating things (menus, sheets). Nothing sits on a soft drop shadow the way Material's default card does.
3. **Depth is a four-step surface ramp, not elevation-tinting.** `bg` → `bg-accent` → `card` → `bg-elevated` are four hand-picked near-blacks. They are *not* one colour at four opacities, and not a tonal palette generated from a seed.
4. **Exactly one high-chroma colour.** Mint `#00d492` on a field that is otherwise fully desaturated. Its foreground is **black** (`--accent-fg: #000`), which is what makes it read as a signal light rather than as a button colour.
5. **Semantic roles are named, and colour literals are banned.** 54 tokens with names like `--ok`, `--warn`, `--danger`, `--aim`, `--clarify`, `--diff-add`. The contract is explicit: *"Never a hardcoded `#hex` / `rgb()` / `rgba()` literal."* Roles carry meaning, so a new UI element inherits a meaning rather than picking a colour.
6. **Instrument, not chrome.** Metrics are mono and tabular. Data colour is *mixed into the surface* rather than saturated on top of it — Crew's session-breakdown hues are each `color-mix(in srgb, <hue> 74%, var(--card))` specifically so a chart reads as "a calm instrument trace on any theme … rather than a saturated slop-gradient." Copy that instinct: on a phone this is the difference between a status bar and a toy.
7. **Motion is short, eased-out, and never decorative.** One entrance curve does most of the work: `cubic-bezier(.16, 1, .3, 1)` at 200–350ms. The exceptions earn their exception (a sheet gets an iOS curve; an option chip gets an overshoot).

If a screen you built has a soft grey drop shadow, a second accent hue, a saturated chart, or a 500ms transition, it has left the direction.

---

## 2. Colour

### 2.1 The one architectural decision: don't use `ColorScheme` alone

Material 3's `ColorScheme` has ~30 slots built around a tonal system. Crew's palette is 54 hand-picked roles that are **not** tonally derived. Forcing Crew's roles into M3's slots loses two thirds of them and mangles the rest.

So carry both:

```kotlin
// app/src/main/java/dev/kiro/android/ui/theme/KiroColors.kt

@Immutable
data class KiroColors(
    // surfaces (the four-step ramp)
    val bg: Color, val bgAccent: Color, val bgElevated: Color, val bgHover: Color,
    val card: Color, val cardFg: Color, val cardHl: Color,
    val chrome: Color,
    // text
    val text: Color, val textStrong: Color,
    val muted: Color, val mutedFg: Color, val mutedStrong: Color,
    // lines
    val border: Color, val borderStrong: Color,
    // the one accent
    val accent: Color, val accentFg: Color, val accentHover: Color,
    val accentSubtle: Color, val accentGlow: Color, val ring: Color,
    // semantic roles
    val ok: Color, val okFg: Color, val okSubtle: Color,
    val warn: Color, val warnFg: Color, val warnSubtle: Color,
    val danger: Color, val dangerFg: Color, val dangerSubtle: Color,
    val info: Color, val infoFg: Color, val infoSubtle: Color,
    val aim: Color, val aimFg: Color, val aimSubtle: Color,
    val clarify: Color, val clarifySubtle: Color,
    // diffs (F-17)
    val diffAdd: Color, val diffAddText: Color,
    val diffDel: Color, val diffDelText: Color,
    val diffHunk: Color, val diffHunkText: Color, val diffMetaText: Color,
)

val LocalKiroColors = staticCompositionLocalOf<KiroColors> { error("KiroTheme missing") }

object KiroTheme {
    val colors: KiroColors @Composable @ReadOnlyComposable get() = LocalKiroColors.current
}
```

`KiroTheme` also emits a derived M3 `ColorScheme` (mapping `accent`→`primary`, `accentFg`→`onPrimary`, `card`→`surface`, `danger`→`error`, and so on) so that stock M3 components — `Switch`, `Slider`, `TextField`, `DatePicker` — are not visually stranded. **Read `KiroTheme.colors` in your own composables; the `ColorScheme` exists only to feed the components you didn't write.**

Three rules that follow, and they are the whole point of the indirection:

- **`Color(0xFF…)` appears in exactly one file** (`Palette.kt`). Anywhere else it is a bug, exactly as in Crew's contract. Enforce with a Lint rule or a CI grep — the same posture ADR-003 takes on `core/` purity, and for the same reason.
- **Dynamic colour is OFF.** No `dynamicDarkColorScheme()`. Material You would replace a hand-tuned neutral ramp and a deliberate single accent with a palette seeded from the user's wallpaper. That is a different design, not a personalised version of this one. This is a real departure from Android's default advice, taken knowingly.
- **A new colour role goes into `KiroColors` for *both* themes at once**, or not at all. Crew guards this with a parity test; do the same (see §9).

### 2.2 The values

Copied from KiroCrew `website/src/index.css`, default theme. These are the starting point, not a suggestion to re-pick.

**Dark** (`:root, [data-theme="dark"]`):

| Role | Value | | Role | Value |
|---|---|---|---|---|
| `bg` | `#12141a` | | `accent` | `#00d492` |
| `bgAccent` | `#14161d` | | `accentFg` | `#000000` |
| `bgElevated` | `#1a1d25` | | `accentHover` | `#34d399` |
| `bgHover` | `#262a35` | | `accentSubtle` | `rgba(4,117,88,.20)` |
| `card` | `#181b22` | | `accentGlow` | `rgba(4,117,88,.35)` |
| `cardFg` | `#f4f4f5` | | `ring` | `#10b981` |
| `cardHl` | `rgba(255,255,255,.05)` | | `ok` / `okFg` | `#22c55e` / `#000` |
| `chrome` | `rgba(18,20,26,.95)` | | `warn` / `warnFg` | `#eab308` / `#000` |
| `text` | `#e4e4e7` | | `danger` / `dangerFg` | `#ef4444` / `#000` |
| `textStrong` | `#fafafa` | | `info` / `infoFg` | `#0891b2` / `#000` |
| `muted` | `#7f7f88` | | `aim` / `aimFg` | `#a78bfa` / `#000` |
| `mutedStrong` | `#52525b` | | `clarify` | `#eab308` |
| `border` | `#27272a` | | `diffAdd` / text | `rgba(46,160,67,.15)` / `#7ee787` |
| `borderStrong` | `#3f3f46` | | `diffDel` / text | `rgba(248,81,73,.15)` / `#ffa198` |
| | | | `diffHunk` / text | `rgba(4,117,88,.20)` / `#6ee7b7` |

All `*Subtle` fills are the role hue at **.12 alpha** (`aim` at .15, `clarify` at .08).

**Light** (`[data-theme="light"]`):

| Role | Value | | Role | Value |
|---|---|---|---|---|
| `bg` | `#fafafa` | | `accent` | `#047558` |
| `bgAccent` | `#f5f5f5` | | `accentFg` | `#ffffff` |
| `bgElevated` | `#ffffff` | | `accentHover` | `#059669` |
| `bgHover` | `#f0f0f0` | | `accentSubtle` | `rgba(4,117,88,.12)` |
| `card` | `#ffffff` | | `ring` | `#047558` |
| `cardFg` | `#18181b` | | `ok` / `okFg` | `#16a34a` / `#000` |
| `cardHl` | `rgba(0,0,0,.03)` | | `warn` / `warnFg` | `#a16207` / `#fff` |
| `chrome` | `rgba(250,250,250,.95)` | | `danger` / `dangerFg` | `#dc2626` / `#fff` |
| `text` | `#3f3f46` | | `info` / `infoFg` | `#0891b2` / `#000` |
| `textStrong` | `#18181b` | | `aim` / `aimFg` | `#7c3aed` / `#fff` |
| `muted` | `#71717a` | | `clarify` | `#a16207` |
| `border` | `#e4e4e7` | | `diffAdd` / text | `rgba(22,163,74,.12)` / `#1a7f37` |
| `borderStrong` | `#d4d4d8` | | `diffDel` / text | `rgba(220,38,38,.12)` / `#cf222e` |
| | | | `diffHunk` / text | `rgba(4,117,88,.12)` / `#065f46` |

Note the accent **changes hue between themes** — mint `#00d492` on dark, a much deeper `#047558` on light — because the light theme needs contrast against white, not the same colour dimmed. Don't "fix" this into one value.

### 2.3 Code and diff colour (F-17)

ADR-002 §3 flags syntax highlighting as native's weakest row and asks for it to be *"a named work item with a real estimate."* Crew's highlighter palette removes the design half of that problem — the token→colour mapping is already chosen and is theme-independent:

| Token class | Colour | | Token class | Colour |
|---|---|---|---|---|
| keyword, built-in, tag | `#c678dd` | | title, function | `#61afef` |
| string, attr, addition | `#98c379` | | type, params | `#e5c07b` |
| number, literal, regexp | `#d19a66` | | meta | `#e06c75` |
| comment, quote | `muted`, *italic* | | section | `accent` |

JSON payloads (useful for the diagnostics viewer in F-20) get their own set: key `#9CDCFE`, string `#CE9178`, number `#B5CEA8`, boolean `#569CD6` on dark; `#001080` / `#A31515` / `#098658` / `#0000FF` on light.

F-17 still owns the hard part — a Compose highlighter or a contained WebView — but it inherits the palette rather than inventing one.

---

## 3. Type

**Faces:** `Space Grotesk` (body) and `JetBrains Mono` (code). Both are SIL OFL and ship as `res/font` resources, so the app does not depend on the OS having them.

```kotlin
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium,  FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
)
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium,  FontWeight.Medium),
)
```

Ship **only the weights you use**. Crew uses 400/500/600 proportional and 400/500 mono; each extra face is APK size for nothing.

**Scale**, mapped from Crew's (`website/docs/frontend-conventions.md` §Typography):

| Use | Size | Family | M3 slot |
|---|---|---|---|
| Body, transcript prose, descriptions | **14sp** | Space Grotesk | `bodyMedium` |
| Labels, buttons, list rows | **13sp** | Space Grotesk Medium | `labelLarge` |
| Badges, captions, timestamps | **12sp** | Space Grotesk | `labelMedium` |
| Code, diffs, inline code, metrics | **13sp** | JetBrains Mono | `bodySmall` |
| Screen title | **20sp** SemiBold | Space Grotesk | `titleLarge` |
| Section header | **15sp** Medium | Space Grotesk | `titleSmall` |

**Floor: 11sp. Nothing below it, ever.** Crew's floor is 11px with 10px reserved for purely decorative glyphs; on a phone held at arm's length, drop the decorative exception and make 11sp absolute.

Two Android-specific consequences Crew doesn't have to deal with:

- **`sp`, not `dp`, for every piece of text** — and that means the scale above is a *design* size, not a rendered one. A user at 200% font scale renders 14sp as 28sp. Every text container must wrap or scroll rather than clip. Check the transcript, the approval card, and the session-list row at 200% before calling any of them done (F-21).
- **Numbers use `TextStyle(fontFeatureSettings = "tnum")`** wherever they update in place — token counts, elapsed time, diff `+/-` counts. Crew uses `tabular-nums` for exactly this. Without it a live counter jitters horizontally on every tick.

---

## 4. Shape, elevation, and the hairline

**Radii** — Crew's four, verbatim:

```kotlin
val KiroShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // --radius-sm : chips, pills, small buttons
    small      = RoundedCornerShape(8.dp),   // --radius-md : buttons, inputs, list rows
    medium     = RoundedCornerShape(12.dp),  // --radius-lg : cards, approval cards
    large      = RoundedCornerShape(16.dp),  // --radius-xl : sheets, dialogs, tool blocks
)
```

These are noticeably tighter than Material 3's defaults (M3 cards are 12dp but its buttons are fully rounded at 20dp+). **Do not use `RoundedCornerShape(50)` / `CircleShape` on a button.** A pill-shaped filled button is the single fastest way to make a screen stop looking like Crew — Crew's buttons are 6–8dp rectangles.

**Elevation: use borders.** The default surface treatment is:

```kotlin
Modifier
    .clip(KiroShapes.medium)
    .background(KiroTheme.colors.card)
    .border(1.dp, KiroTheme.colors.border, KiroShapes.medium)
```

`Card(elevation = …)` with M3's default tonal elevation is wrong here — it tints the surface toward the primary hue, which on this palette pushes cards green. Set `CardDefaults.cardColors(containerColor = colors.card)` and `elevation = CardDefaults.cardElevation(0.dp)`, then draw the border.

Shadows are reserved for genuinely floating layers. Crew's three, translated:

| Token | Dark | Light | Use |
|---|---|---|---|
| `shadow-sm` | 1dp, α.20 | 1dp, α.06 | hover/pressed lift — rarely needed on touch |
| `shadow-md` | 4dp blur 12, α.25 **+ 1px inner light ring** | 4dp, α.08 + ring α.04 | dropdowns, popovers |
| `shadow-lg` | 12dp blur 28, α.35 | 12dp, α.12 | bottom sheets, dialogs |

The inner hairline in `shadow-md` is load-bearing on dark: a pure drop shadow is invisible against `#12141a`, so the ring is what actually separates a floating menu from the page. In Compose that is a `.border(1.dp, Color.White.copy(alpha = .03f))` on the elevated surface, not something `Modifier.shadow()` gives you.

**Focus ring:** border switches to `ring` plus a 3dp `accentSubtle` glow. Keyboard/D-pad only (`Modifier.onFocusChanged` gated on focus, not on press) — Crew keys it to `:focus-visible` specifically so a pointer tap leaves no ring, and a touch tap should behave the same way.

---

## 5. Motion

One curve does most of the work:

```kotlin
val KiroEase   = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)   // entrances, state changes
val SheetIn    = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)  // panels arriving
val SheetOut   = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // panels leaving
val ChipHop    = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f) // overshoot
```

| Crew animation | Spec | Android use |
|---|---|---|
| `scale-in` | 200ms `KiroEase`, `scale .92→1` + fade | approval card appearing (F-14) |
| `rise` | 350ms `KiroEase`, `translateY 8dp→0` + fade | list items, first paint |
| `slide-up` | 300ms `KiroEase`, `translateY 12dp→0` + fade | sheets, inline panels |
| sheet in / out | **420ms** `SheetIn` / **240ms** `SheetOut` | bottom sheets, side panels |
| `chip-hop` | 420ms `ChipHop`, overshoot **4dp past rest** at 55% | suggestion chips (F-13) |
| `dot-breathe` | 2s ease-in-out, α .6↔1 + scale .9↔1.1 | live-session status dot |
| caret blink | **1.1s** step-end | streaming caret (F-12) |

Note the asymmetry: **panels arrive slower than they leave** (420 in, 240 out). Crew is explicit that these two curves are shared by every sliding surface in the app — "move both or neither." Put them in one file and do the same.

**Reduced motion is two settings on Android, not one.** Read `Settings.Global.ANIMATOR_DURATION_SCALE` (0f means animations off) *and* `Settings.Global.TRANSITION_ANIMATION_SCALE`. Expose it as `LocalReduceMotion` and, when set:

- zero **durations and delays**, not just durations. Crew hit this exactly: its global reduced-motion rule zeroed duration but not delay, and staggered entrances with `fill-mode: backwards` stayed *fully invisible* for the length of their delay. A Compose stagger built on `delayMillis` has the identical bug.
- replace the streaming caret's blink with a **static caret at 90% opacity** (Crew's own fallback) rather than removing it — it is a state indicator, not decoration.
- keep the live-session dot's *colour*, drop its pulse.

**One rule specific to the transcript:** the streaming node must have **no animation at all** while it is streaming. ADR-003 §3 already requires coalescing chunks on a 60–100ms tick and hoisting the in-flight message out of the lazy list; adding a shimmer or a fade to that node reintroduces per-frame recomposition on the exact node that is already the app's hottest. The caret is the one moving thing, and it animates opacity only.

---

## 6. Layout and density on a phone

Crew's `page-layout.md` is written narrow-first and its numbers transfer directly.

**Gutters.** 16dp screen gutter. A card inside it adds 8dp horizontal (not 16, not 20) and keeps 20dp vertical. **Total inset before body text: ~25dp, and that is a budget, not an average.** The reasoning: at 390dp, a 24dp gutter plus a 20dp card inset puts text 44dp from the edge — 88 of 390dp, 22.6% of the screen, spent on nothing. On the widest phones and on tablets (F-21), widen the gutter to 24dp; the card inset can go to 20dp there.

**One left edge.** Uncontained content — a section heading, a list row, the transcript's message text, the composer — all sit on the 16dp gutter. Only a bordered card steps inside it. Crew notes the corollary and it matters more on Android than on the web: **reach for a card less often on a phone.** A phone list of sessions wants rows with hairline dividers, not eleven stacked bordered cards.

**Verify at 320dp, not 390dp.** Crew found a text column that measured 34px at 390 and **0px at 320**. Add a 320dp preview next to every screen's default preview.

**Fixed numbers worth keeping:**

| Crew constant | Value | Android meaning |
|---|---|---|
| `TOPBAR_HEIGHT` | 52 | top app bar height — shorter than M3's 64dp default |
| `MAX_MESSAGE_WIDTH` | 820 | transcript column cap on tablets/foldables (F-21) |
| status dot | 8dp | live-session indicator |
| tool-block header | min 36dp, mono 12sp | see §7 |

**An unbounded action row leaves the text row; it does not shrink it.** A row of actions whose count depends on state takes its natural width and starves the text column. Crew measured this at 34px of remaining text at 390 and 0 at 320. On a session-list row with a variable number of state chips, put the chips on their own line rather than letting them compete with the repo name.

**Insets, not viewport units.** Crew's `100vh`/`svh`/`dvh` distinction has a direct Android analogue and the same failure mode. Go edge-to-edge (`enableEdgeToEdge()`), then:

- app shell → `Modifier.safeDrawingPadding()`
- composer → `Modifier.imePadding().navigationBarsPadding()` (F-13)
- the transcript scroll container is the surface that must track the *visible* area, so it consumes IME insets rather than being padded by the shell

Safe area is **padding, not size** — the same rule Crew states, and it is what keeps the composer's send button above the gesture bar.

---

## 7. Screen-by-screen

### Transcript (F-12) — the screen that defines the app

- **No chat bubbles.** Crew's messages are full-width rows on the page ground, separated by space and a hairline, with role carried by a small mono label and colour — not by an opposing-alignment bubble. A bubble UI would read as a messaging app; this is a work log.
- Message text at 14sp on `bg`; the user's own turns get `card` and a border, so the *agent* is the page and the *user* is the interjection.
- **Tool calls are instrument blocks**, and Crew's is worth copying nearly verbatim: `RoundedCornerShape(16.dp)`, `border(1.dp, border)`, `background(bgElevated)`, `clipToBounds()`, with a header strip that is **mono 12sp, `muted`, min 36dp tall**, on a fill mixed 50/50 between `bgElevated` and `bg`. Content below it. The strip is what makes a tool call read as instrumentation rather than as another message.
- Entrance for a tool block is a **600ms opacity fade** (Crew's `ft-fade`) — slower than everything else on purpose, because a block appearing mid-stream should not snap.
- Streaming caret: 2dp × 1.05em bar in `accent`, 1dp radius, blinking at 1.1s step-end.
- Unknown update kinds render as a **generic entry**, styled like a tool block with a `muted` header. ADR-003 §3 requires the tolerance; this says what it looks like. A protocol addition must be a cosmetic gap, and a cosmetic gap still has to be designed.

### Approval / permission (F-14) — the highest-stakes surface

Crew's `ApprovalCard` is a precise idiom and it maps cleanly:

```
background card · border 1dp border · LEFT border 3dp in the semantic tone
RoundedCornerShape(8.dp) · padding 14dp horizontal, 10dp vertical · 14sp
entrance: scale-in (200ms, KiroEase)
```

The **3dp left edge in the role colour** (`warn` pending, `ok` approved, `danger` rejected) is the signature. After a decision the buttons are replaced by a 13sp `muted` line stating the outcome — the card does not disappear, because the transcript is a record.

Two Android-specific requirements that Crew has no equivalent for:

- The same information must survive into a **notification** (F-16), where none of this styling exists. Design the notification first — title, the command being requested, two actions — and treat the in-app card as the richer view of the same content. A one-line accessible summary is mandatory either way.
- Approve and Reject must be **48dp targets that are not adjacent**. This is the one place in the app where a mis-tap has consequences an undo cannot reach.

### Session list (F-10)

Rows, not cards (see §6). Each row: repo name at 14sp `textStrong`, branch/model at 12sp `muted` mono, relative time right-aligned and `tnum`. Live sessions get the 8dp `dot-breathe` dot in `ok`; idle sessions get a static `muted` dot. Hairline divider between rows, no shadow. Swipe-to-delete follows the platform, with a `danger`-tinted background — Crew has no swipe idiom, so this is Android's to own.

### New session (F-11)

The densest form in the app (repos, model, autonomy level). Use `bg` sections with 13sp `muted` labels and hairline dividers rather than a stack of bordered cards — the card-per-field pattern is exactly what §6's "reach for a card less often" is warning about. Autonomy level is the one control that should carry colour: map its steps onto `ok` → `warn` → `danger` so the risk gradient is visible without reading.

### Composer (F-13)

`bgElevated`, 8dp radius, 1dp border going to `accent` on focus. Suggestion chips above it use `chip-hop` with a stagger — and that stagger is the exact case §5 warns about under reduced motion. Send button is `accent` with **black** content (`accentFg`), 44×44dp minimum in a 48dp row.

### Diffs and code (F-17)

Use the `diff-*` tokens. The added/removed **fills are ~12–15% alpha** — deliberately faint, with the signal carried by the `diffAddText` / `diffDelText` glyph colour. A saturated green/red row background is the wrong direction. Line numbers in `muted` mono with `tnum`. Code blocks scroll horizontally rather than wrapping — Crew's explicit choice, and correct here too. Diff stats use Crew's compact cell sparkline: 7dp squares at 2dp radius, `ok` / `danger` / `border`.

### Ambient background — optional, and get it right or skip it

Crew's page ground carries three very faint radial gradients of the accent at **4%, 2.5% and 2%**, drifting on a 20s loop. It is what stops the dark from reading as flat black. On Android:

- render it **once**, in the root scaffold, behind everything — never per-screen
- keep the alphas exactly this low; at 8% it becomes a green tint and the design is gone
- the 20s drift is the first thing to drop under reduced motion, and the first thing to drop for battery on a foreground-service screen
- if it can't be done cheaply, a flat `bg` is a perfectly acceptable outcome — this is the one item in this document that is genuinely optional

---

## 8. Touch targets and accessibility (F-21)

**Crew says 44px. Android says 48dp. Use 48dp.**

This is a real conflict, not a rounding difference. Crew's 44px comes from Apple HIG and from WCAG 2.2 SC 2.5.8, whose hard floor is 24×24 with a spacing exception. Android's accessibility scanner flags anything under 48×48dp, and Material's touch-target expansion assumes 48. On a platform whose own tooling audits at 48, shipping 44 means arguing with the scanner on every build.

Keep Crew's *grading*, which is the genuinely useful part of its rule:

- **48dp** for anything that changes state — approve, reject, send, delete, model selection.
- **A visual element may be smaller than its target.** A 16dp icon inside a 48dp touch area is correct and is how the density is preserved. Use `Modifier.minimumInteractiveComponentSize()` or explicit padding; do not scale the glyph up to fill the target.
- **Under 24dp *and* crowded is a conformance failure**; under 48dp alone is a convention miss. Crew's note that reporting every sub-44 control as a violation over-reports by ~3× applies to triage here too.

Also carried over:

- Every icon-only button needs a `contentDescription`. **An icon alone cannot carry a state-changing action** — approve/reject/delete get text labels, always.
- Streaming content is a live region: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the in-flight node. Announcing every token is worse than announcing nothing, so announce on `TurnEnd` and on approval requests only.
- Contrast is checked in **both** themes. The light theme's `muted` `#71717a` on `bg` `#fafafa` is the tightest pair in the set and is where a regression will land first.

---

## 9. Themability — how far to follow Crew

Crew's theme system is large: 54 validated CSS vars, installable packs, sandboxed overlays, three capability tiers. **Do not port the pack system.** It exists because Crew is a self-hosted dashboard where users install themes from GitHub; an Android client has no equivalent need and every part of that pipeline (sandboxed iframes, a CSS tokeniser, an install validator) is a security surface bought for nothing.

Port the part that costs nothing and pays immediately:

- **the token indirection** — every colour via `KiroTheme.colors`, one `Palette.kt`
- **the dark/light parity rule** — a role that exists in one theme exists in both
- **a parity test.** Crew guards its allowlist with a test asserting set equality across two languages. The Android version is trivial and worth having on day one: reflect over `KiroColors`, assert every property is non-default in both themes, and assert no `Color(0xFF…)` literal appears outside `Palette.kt`.

That leaves the door open. If user themes are ever wanted, `KiroColors` is already the surface they would fill.

---

## 10. Where this is likely to be wrong

Stated so the next person can push back with evidence rather than taste:

1. **The ambient gradient may not be worth its cost.** It is one of Crew's most recognisable properties and also the most expensive thing here to render continuously behind a live transcript. Measure it on a low-end device during streaming before committing; §7 already says dropping it is acceptable.
2. **Backdrop blur is not really available.** Crew's `topbar-glass` is `blur(18px) saturate(1.8)` over a 74%-opacity chrome. Android has no cheap backdrop blur below API 31, and minSdk is 26 (ADR-003). **Default to a solid `chrome` bar with a 1dp bottom border**, and treat a `RenderEffect` blur as an API-31+ enhancement, if at all. Do not build a layout that only reads correctly with blur.
3. **13sp labels may be too small at Android's default density.** Crew's 13px sits in a desktop browser at a desk. If device testing says the session list is hard to scan, the label tier moves to 14sp — and that is a scale change made once, in `Type.kt`, not per screen.
4. **The accent's black foreground is unusual and will attract "fixes."** `#000` on `#00d492` is deliberate and is a large part of why the accent reads as a signal. It is also the thing a reviewer will most often flag as a mistake. It isn't one.
5. **Nothing here is validated against a real Kiro Crew screenshot at phone width.** This is derived from Crew's tokens, contracts and component source, which is a strong basis for colour, type, shape and motion — and a weaker one for composition. The screen-by-screen hints in §7 are the part most likely to need adjustment once someone puts the two side by side.

---

## 11. Checklist for a UI work item

Before marking any `app/ui/**` item done:

- [ ] No `Color(0xFF…)` outside `Palette.kt`; every colour via `KiroTheme.colors`
- [ ] Renders correctly in **both** themes
- [ ] Previews at **320dp** and 390dp, and at **200% font scale**
- [ ] Surfaces separated by 1dp `border` hairlines, not drop shadows; no M3 tonal elevation
- [ ] Radii from `KiroShapes` (6/8/12/16) — no pill-shaped buttons
- [ ] State-changing controls ≥ 48dp; icon-only controls have `contentDescription`
- [ ] Motion uses `KiroEase` / `SheetIn` / `SheetOut`; nothing over 420ms
- [ ] Reduced motion zeroes **delays as well as durations**
- [ ] Live-updating numbers use `tnum`
- [ ] Insets handled with `safeDrawingPadding()` / `imePadding()`, not fixed heights

---

*Palette, type scale, radii, shadow and motion values are transcribed from [`kirodotdev/KiroCrew`](https://github.com/kirodotdev/KiroCrew) (`website/src/index.css`, `website/tailwind.config.js`) and its `website/docs/theming-contract.md`, `website/docs/page-layout.md` and `website/docs/frontend-conventions.md`. KiroCrew is Apache-2.0. This document restates design values for a separate, unaffiliated client; it copies no code.*
