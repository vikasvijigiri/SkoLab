# Skolab Component Rules

## Buttons
- One primary button per screen maximum.
- Ghost button for secondary actions that live alongside a primary button.
- Text button for tertiary actions: Skip, Cancel, View all, Sign in.
- Never stack two primary buttons. If you need two CTAs, use primary + ghost.
- Disabled state must use color_disabled_bg — never reduce opacity of the active button.

## Cards
- Standard card for researcher profiles, content items, and data records.
- Hero card (blue background) for the top stat block on Analytics screen only.
- Subtle card for secondary information blocks, saved counts, filter summaries.
- Prediction card has a left border accent — use only for AI prediction output.
- Never give cards elevation — 0dp elevation everywhere. Border is depth.

## Inputs
- Default input style for all text entry fields.
- Search input style for search bars — always shows the search icon.
- Error state only triggered after the user has interacted and left the field.
- Never show error state on an untouched field.

## Chips
- Default chip for topic tags, domain labels, filter options.
- Selected chip when a filter is actively applied.
- Match chip exclusively for displaying match percentage scores.
- Chips in a horizontal scroll row must have 6dp gap between them.
- Never use chips as navigation — use tabs for navigation patterns.

## Navigation
- Bottom nav: 4 items maximum. Labels always visible (no icon-only nav).
- Active nav item uses color_primary icon + label + subtle indicator pill.
- Inactive nav items use color_text_muted for both icon and label.
- Top bar title aligns left always — never centered.
- Back navigation uses color_primary chevron, never a close X.
