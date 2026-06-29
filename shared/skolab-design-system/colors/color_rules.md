# Skolab Color Rules

## The palette in one sentence
Every color in this app is a shade of blue. There are no neutral grays —
even the most muted surfaces carry a blue tint. This creates visual
coherence and the trust signal the app is built on.

## Token usage rules

| Token | Use for | Never use for |
|---|---|---|
| color_bg_page (#F7F9FF) | Screen root backgrounds only | Cards, modals, drawers |
| color_bg_surface (#FFFFFF) | Cards, sheets, dialogs | Screen backgrounds |
| color_bg_subtle (#EEF3FE) | Input fields, chips, skeleton loaders, inactive tabs | Primary actions |
| color_primary (#2D6BE4) | Primary buttons, active nav, links, FAB, selected state | Backgrounds of full screens |
| color_primary_pressed (#1A4FA8) | Pressed/ripple state of primary only | Default state of anything |
| color_primary_deep (#0D2E6B) | Logo, H1 headings, most important text | Body copy, descriptions |
| color_text_secondary (#4A6FA5) | Body copy, card descriptions | Headings or key values |
| color_text_muted (#8BA7D4) | Placeholders, timestamps, helper text | Anything interactive |
| color_border (#D4E2F8) | Card strokes, dividers, separators | Backgrounds or text |
| color_accent_streak (#A8FF3E) | Progress bars and streak indicators ONLY | Buttons, icons, text |
| color_accent_badge (#D42B2B) | Notification dot badges ONLY | Errors, destructive actions |
| color_accent_match_bg/text | Match percentage chips only | Any other chip type |

## Hard rules
1. Never use pure neutral gray anywhere. All grays must be blue-tinted.
2. Never place color_text_primary (#0D2E6B) on color_primary (#2D6BE4) — it disappears.
3. Text on any blue surface must use color_text_on_primary or color_text_on_primary_sub.
4. color_accent_streak (#A8FF3E) appears on maximum 1 element per screen.
5. color_accent_badge (#D42B2B) is for notification dots only — never repurpose it for errors or warnings.
6. No drop shadows anywhere. Depth is expressed through border strokes only.
7. Screen backgrounds are always color_bg_page. Never color_bg_surface at root level.
