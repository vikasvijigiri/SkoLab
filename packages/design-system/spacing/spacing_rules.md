# Skolab Spacing Rules

## Grid system
Base unit is 4dp. Every spacing value must be a multiple of 4.
Never use arbitrary values like 6dp, 10dp, 14dp, 18dp.

## Screen layout
- Horizontal screen margin: 16dp on all screens
- Top content margin below toolbar: 12dp
- Bottom content margin above nav bar: 16dp
- Section gap (between two card groups): 24dp
- Item gap (between cards in a list): 8dp

## Card rules
- Internal padding: 16dp horizontal, 14dp vertical
- Border width: 0.5dp (always — no thicker borders)
- Corner radius: 12dp standard, 16dp for hero cards
- Never add elevation/shadow — border is the only depth signal

## Component heights
- All tappable elements minimum height: 48dp (accessibility)
- Primary and ghost buttons: 52dp
- Input fields: 52dp
- Chips: 32dp
- Bottom nav bar: 64dp including system gesture area

## Density rules
- Compact list items: 48dp height, 12dp vertical padding
- Standard list items: 56dp height, 16dp vertical padding
- Never pack two different font sizes into less than 40dp height
