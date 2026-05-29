# Skolab Icon Rules

## Library
Use Material Symbols (Rounded variant) exclusively.
Do not mix Outlined and Rounded variants in the same screen.

## Sizes
- 16dp: inline icons inside chips, badges, and caption rows
- 20dp: list item leading icons, input field icons
- 24dp: navigation bar icons, top bar action icons
- 28dp: empty state illustrations (compose from icons)

## Colors
- Interactive icons: color_primary (#2D6BE4)
- Decorative / informational icons: color_text_muted (#8BA7D4)
- Icons on color_bg_hero surfaces: color_text_on_primary (#FFFFFF)
- Notification bell with badge: color_primary icon, color_accent_badge dot
- Error state icons: color_error (#D42B2B) only in error contexts

## Rules
1. Icons must never be used alone as navigation without a text label.
2. Every icon-only button must have a content description for accessibility.
3. Do not tint icons with color_text_primary (#0D2E6B) — too heavy.
4. Icon stroke weight must stay at the default Weight 400 in Material Symbols.
5. Never scale icons between the defined size steps — use exactly 16, 20, 24, or 28dp.
