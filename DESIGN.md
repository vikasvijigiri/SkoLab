# DESIGN.md — SkoLab web

**Status:** adopted 2026-09-23. This light evidence-workspace direction
supersedes the previous dark-first direction for new and redesigned screens.

## Product feeling

SkoLab is a calm, evidence-first workspace for researchers: a well-organised
research desk, not a terminal, social feed, or generic AI dashboard. The
product-owner reference establishes the direction: a warm neutral ground, one
rounded workspace shell, navy structure, teal evidence states, compact
workflow navigation, and a primary task surface with inspectable context.
Adapt that language to SkoLab; never copy the reference product's identity.

## System

- Light workspace is the default. A future dark theme needs a separate contrast
  audit and a dated decision.
- The primary canvas owns the task; rails contain navigation, sources, and
  inspection context. Do not force three columns in focus mode or mobile.
- Evidence includes explicit provenance, citations, confidence, and decisions.
  Never communicate status through colour alone.
- Use calm density and real research copy. Avoid decorative card grids,
  gradients, glass, oversized padding, or generic AI styling.
- One filled primary action is allowed per viewport.

## Semantic tokens

| Token | Value | Use |
|---|---:|---|
| `--page-bg` | `#F6F7F5` | Warm app ground |
| `--workspace-bg` | `#FBFCFA` | Rounded workspace shell |
| `--surface` | `#FFFFFF` | Panels, dialogs, fields |
| `--surface-subtle` | `#F4F7F8` | Insets and quiet rows |
| `--surface-evidence` | `#EDF8F5` | Evidence and verification |
| `--surface-selected` | `#EAF0FA` | Selected rows |
| `--border-color` | `#DCE5E8` | Hairlines and borders |
| `--border-strong` | `#C7D4D9` | Hover/active borders |
| `--text-primary` | `#19243A` | Headings/body |
| `--text-secondary` | `#52637D` | Supporting copy |
| `--text-muted` | `#71819A` | Captions/eyebrows |
| `--brand-navy` | `#19243A` | Header ribbon and decisive actions |
| `--primary` | `#19243A` | Links and primary actions |
| `--primary-muted` | `#EAF3F1` | Primary selection background |
| `--accent-signal` | `#43827A` | Evidence action/active workflow |
| `--success` | `#43827A` | Verified/saved state |
| `--warning` | `#AA6A12` | Caution |
| `--danger` | `#BE3F47` | Error/destructive action |
| `--ring` | `#3A8580` | Keyboard focus |

Body text must meet WCAG AA 4.5:1; large text and controls need 3:1. Teal is
for evidence and verified workflow—not a competing CTA. Coral is the decisive
action, never decoration.

## Type and layout

- **Geist**: all readable prose/interface text (400/500/600/700).
- **Geist Mono**: tabs, workflow numbers, labels, IDs, timestamps, DOI,
  keyboard hints, data values, and table headings.
- Scale: page title 30/1.15/700; section 20/1.25/600; card 16/1.3/600; body
  14/1.55/400; dense metadata 12.5/1.5; eyebrow 11px uppercase mono.
- Spacing only: `4, 8, 12, 16, 24, 32, 48, 64, 96`.
- Desktop workspace: 24px-radius outer shell, 1px border, up to 1800px wide;
  header ribbon 64–72px; source rail 240–280px; inspector 300–360px. Mobile
  is single-column; contextual inspectors become a sheet or route.
- Panels use white fill, a 1px border, 8px radius, and only a restrained
  shadow: `0 1px 2px rgba(23,35,58,.05), 0 2px 6px rgba(23,35,58,.04)`.

## Components and behaviour

- Header ribbon: navy, brand left, compact contextual status when useful,
  profile/menu right; hairline below.
- Workflow navigation: numbered mono tabs; active state uses weight and an
  underline/rule, not a saturated pill.
- Buttons: coral filled primary, teal outline secondary, text tertiary. Use
  44px minimum touch targets, 8px radius, and a visible 2px focus ring.
- Inputs: white surface, 1px border, 40px minimum height, mono eyebrow label.
- Evidence cards show source/person, claim/answer, citation context, and an
  inspect action. Empty inspectors explain the next step.
- Motion: 120ms fast, 200ms normal, 320ms slow; colour plus at most a 1px lift.
  Never scale controls. Respect `prefers-reduced-motion`.

## Release gate

- Semantic landmarks, one `<h1>`, keyboard-reachable controls, named icons,
  visible focus, and correct focus order are mandatory.
- Ship loading, empty, error, offline, permission, and success states with
  useful copy and a next action.
- Verify 320/768/1024/1440px, keyboard flow, axe/WCAG AA, typecheck, lint,
  tests, build, and contrast checks. Do not weaken checks to pass a release.
