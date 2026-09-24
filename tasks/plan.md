# Light Evidence Workspace implementation plan

## Scope

Implement the first production slice of the approved product-design audit:
`platform-foundation` and the desktop-first CoLab manuscript workspace. The
source of truth is `docs/plans/2026-09-17-skolab-product-design-audit.md`; the
visual contract is `DESIGN.md` (adopted 2026-09-23).

## Objective

Make the current web frontend feel like a calm, high-density evidence
workspace: warm page ground, a restrained SkoLab ribbon, white work surfaces,
teal evidence states, and one coral compile action. CoLab must preserve
editable manuscript content, compilation, template, share, and focus controls.

## Dependencies and order

1. Design tokens and shared shell establish the palette and type hierarchy.
2. The CoLab workspace consumes those tokens through a locally scoped module.
3. A component test proves the main landmarks and controls exist.
4. Lint, unit tests, build, contrast, and a local browser screenshot verify
   the result.

## Risks and mitigations

- Existing global CSS contains several legacy themes. Keep the new manuscript
  CSS locally scoped so it cannot regress unrelated routes.
- The workspace is Firebase-authenticated in production. Verify the visual
  route against a locally rendered manuscript component and report any live
  authentication limitation explicitly.
- This does not implement the audit's remaining discovery, identity, network,
  and governance screens. Those are subsequent, independently testable slices.

## Verification checkpoints

- Component test: accessible landmark, editable manuscript field, compile,
  template, share, and focus controls.
- `npm run lint`, `npm run test`, `npm run build`, and `npm run check:contrast`
  from `apps/web`.
- Local browser screenshot at desktop and mobile widths; inspect for clipping,
  contrast, and column overflow.
