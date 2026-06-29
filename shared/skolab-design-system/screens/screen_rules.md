# Skolab Screen-by-Screen Rules

## Onboarding screens
- Background: color_bg_page (#F7F9FF)
- Central illustration container: color_primary at 10% opacity, 80dp circle
- Headline: TextAppearance.Skolab.H1
- Body: TextAppearance.Skolab.Body, max 2 lines
- Progress dots: active = color_primary pill 16dp wide, inactive = color_border circle 6dp
- Single primary CTA at bottom, full width
- Skip text button top right, color_text_muted

## Home / Feed screen
- Background: color_bg_page
- Top bar: flat, no border, logo left + avatar + badge right
- Search bar: color_bg_subtle, borderless, 10dp radius
- Section headers: TextAppearance.Skolab.Micro (ALL CAPS, color_text_muted)
- Researcher cards: standard card style, avatar + name + chips layout
- Horizontal chip scroll for filters: 6dp gap, no scroll indicator

## Researcher Profile screen
- Background: color_bg_page
- Back button top left, share icon top right
- Avatar: 56dp circle, color_primary background, white initials
- Stats row: 2-column grid of standard cards (papers count, h-index)
- Research areas: standard card with tag chip wrapping layout
- Prediction block: prediction card style (left blue border, subtle bg)
- Confidence indicator: filled dots for score, empty for remainder
- Primary CTA: full width "View full analysis" button at bottom

## Analytics / Dashboard screen
- Background: color_bg_page
- Hero stat card at top: full width, color_bg_hero, Display text for number
- Progress bar inside hero: color_primary_deep track + color_accent_streak fill
- 2-column stat grid below hero: standard cards
- Domain breakdown: standard card with label/value rows and dividers
- Period selector: text button top right, color_primary

## Search screen
- Background: color_bg_page
- Search input full width, auto-focused on screen entry
- Recent searches: caption row with clock icon, color_text_muted
- Results list: researcher card style, matches highlighted in color_primary
- Empty state: centered icon (28dp, color_text_muted) + H2 heading + body text

## Settings / Profile screen
- Background: color_bg_page
- User card at top: standard card with large avatar (56dp) + name + institution
- Setting rows: full-width touchable rows, 56dp height, label left + value/toggle right
- Section dividers: color_border, 0.5dp
- Destructive actions (sign out, delete): color_error text, no icon
- Version string: TextAppearance.Skolab.Caption, centered, color_text_muted, bottom
