/**
 * "Open to collaboration" — a self-declared signal a researcher sets on their own
 * profile. There is no data source for it yet (professors populate it once the
 * SkoLab userbase grows). This is the seam: a typed function the card and query
 * already call, returning `{}` today. When a batched
 * `researchers/{uid}.openToCollaboration` lookup exists, it drops in here without
 * touching any caller. See `decisions/0014`.
 */

/**
 * @param ids OpenAlex author ids / ORCIDs in the current result page.
 * @returns `{ id: true }` only for researchers who self-declared. Empty until the
 *          self-declared flag exists — never a fabricated value.
 */
export function resolveCollabFlags(ids: string[]): Record<string, boolean> {
  // TODO(decisions/0014): resolve from researchers/{uid}.openToCollaboration once
  // an OpenAlex-id → SkoLab-uid index and a self-declared flag exist. Until then
  // no id is known to be open — absence means "unknown", never a fabricated flag.
  void ids;
  return {};
}
