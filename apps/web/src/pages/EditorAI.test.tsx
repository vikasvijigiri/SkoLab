import { act, screen, waitFor, within } from "@testing-library/react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { EditorView } from "@codemirror/view";
import { locate } from "../editor/AIPanel";
import type { FakeBackend } from "../test/fakeBackend";
import { renderApp, stubBackend } from "../test/renderApp";

vi.mock("../editor/PdfPreview", () => ({
  PdfPreview: () => <p data-testid="pdf">pdf</p>,
}));

const ADA = "google-ada";
const PARAGRAPH = "We measured teh field at $T<T_c$ and teh results \\cite{k} were clear.";
const SOURCE = `\\documentclass{article}\n\\begin{document}\n\\section{Introduction}\n${PARAGRAPH}\n\nSecond paragraph stays.\n\\end{document}\n`;

let backend: FakeBackend;

function view(): EditorView {
  const dom = screen.getByTestId("latex-editor").shadowRoot?.querySelector<HTMLElement>(".cm-editor");
  const found = dom ? EditorView.findFromDOM(dom) : null;
  if (!found) throw new Error("no editor");
  return found;
}

function source() {
  return view().state.doc.toString();
}

/** Puts the cursor (or a selection) in the source, as a click would. */
function place(anchor: number, head = anchor) {
  act(() => view().dispatch({ selection: { anchor, head } }));
}

async function open(role?: "editor" | "viewer") {
  const id = backend.seed(role ? "grace" : ADA, "My paper", { source: SOURCE, ...(role ? { members: { [ADA]: role } } : {}) });
  const rendered = await renderApp(`/editor/${id}`, async (auth) => {
    await auth.signInWithGoogle();
  });
  await screen.findByTestId("pdf", {}, { timeout: 5000 });
  return { id, ...rendered };
}

describe("AI writing assistant", () => {
  beforeAll(() => {
    Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
    Range.prototype.getBoundingClientRect = () => new DOMRect();
  });
  beforeEach(() => {
    backend = stubBackend(() => Response.json({ status: "compiled", pdf_base64: btoa("%PDF"), log: "" })).backend;
  });

  it("improves the paragraph at the cursor and applies suggestions one by one", async () => {
    const { user } = await open();
    place(SOURCE.indexOf("We measured") + 3);
    await user.click(screen.getByRole("button", { name: "AI" }));
    const sheet = screen.getByRole("dialog", { name: "AI writing assistant" });
    expect(within(sheet).getByText(PARAGRAPH)).toBeInTheDocument();
    expect(await within(sheet).findByText("30 uses left today")).toBeInTheDocument();

    await user.click(within(sheet).getByRole("button", { name: /Grammar/ }));
    const cards = await within(sheet).findAllByRole("listitem");
    expect(cards).toHaveLength(2);
    expect(within(sheet).getByText("2 suggestions")).toBeInTheDocument();
    expect(within(sheet).getByText("29 uses left today")).toBeInTheDocument();

    // The second first: offsets still hold for the other one afterwards.
    await user.click(within(cards[1] as HTMLElement).getByRole("button", { name: "Accept" }));
    expect(source()).toContain("We measured teh field at $T<T_c$ and the results \\cite{k} were clear.");
    await user.click(within(cards[0] as HTMLElement).getByRole("button", { name: "Accept" }));
    expect(source()).toContain("We measured the field at $T<T_c$ and the results \\cite{k} were clear.");
    expect(within(sheet).getAllByText("Applied")).toHaveLength(2);
    expect(within(sheet).getByRole("button", { name: "Accept all" })).toBeDisabled();
  });

  it("finds a suggestion again after nearby edits, and marks it when the text is gone", async () => {
    const { user } = await open();
    const at = SOURCE.indexOf("We measured");
    place(at, at + PARAGRAPH.length);
    await user.click(screen.getByRole("button", { name: "AI" }));
    const sheet = screen.getByRole("dialog", { name: "AI writing assistant" });
    await user.click(within(sheet).getByRole("button", { name: /Clarity/ }));
    const cards = await within(sheet).findAllByRole("listitem");
    // Text inserted before the paragraph shifts everything: found by its context.
    act(() => view().dispatch({ changes: { from: at, insert: "New opening. " } }));
    await user.click(within(cards[0] as HTMLElement).getByRole("button", { name: "Accept" }));
    expect(source()).toContain("New opening. We measured the field at $T<T_c$ and teh results");
    // The other word is rewritten by hand: nothing left to apply it to.
    const second = source().indexOf("teh results");
    act(() => view().dispatch({ changes: { from: second, to: second + 3, insert: "our" } }));
    await user.click(within(cards[1] as HTMLElement).getByRole("button", { name: "Accept" }));
    expect(within(cards[1] as HTMLElement).getByText("Text changed; not applied")).toBeInTheDocument();
    expect(source()).toContain("and our results");
  });

  it("accepts all at once, dismisses, and says when there is nothing to change", async () => {
    const { user } = await open();
    place(SOURCE.indexOf("We measured") + 3);
    await user.click(screen.getByRole("button", { name: "AI" }));
    const sheet = screen.getByRole("dialog", { name: "AI writing assistant" });
    await user.click(within(sheet).getByRole("button", { name: /Concise/ }));
    await user.click(await within(sheet).findByRole("button", { name: "Accept all" }));
    expect(source()).toContain("We measured the field at $T<T_c$ and the results");

    backend.setImprover(() => []);
    await user.click(within(sheet).getByRole("button", { name: /Flow/ }));
    expect(await within(sheet).findByText("Nothing to change")).toBeInTheDocument();
  });

  it("explains limits, a missing passage and read-only access", async () => {
    backend.setAI("improve", { left: 0 });
    const { user } = await open();
    place(SOURCE.indexOf("We measured") + 3);
    await user.click(screen.getByRole("button", { name: "AI" }));
    const sheet = screen.getByRole("dialog", { name: "AI writing assistant" });
    await user.click(within(sheet).getByRole("button", { name: /Academic tone/ }));
    expect(await within(sheet).findByRole("alert")).toHaveTextContent("You've used today's allowance");

    // A blank line has no paragraph.
    place(SOURCE.indexOf("Second paragraph") - 1);
    await user.click(within(sheet).getByRole("button", { name: "Use current selection" }));
    expect(within(sheet).getByText(/Select text in main.tex/)).toBeInTheDocument();
    expect(within(sheet).getByRole("button", { name: /Grammar/ })).toBeDisabled();
  });

  it("is read-only for viewers", async () => {
    const { user } = await open("viewer");
    await user.click(screen.getByRole("button", { name: "AI" }));
    const sheet = screen.getByRole("dialog", { name: "AI writing assistant" });
    expect(within(sheet).getByText(/Only the owner and editors/)).toBeInTheDocument();
    await user.click(within(sheet).getByRole("tab", { name: "Originality" }));
    expect(within(sheet).queryByRole("button", { name: /Run originality check/ })).not.toBeInTheDocument();
  });

  it("runs an originality check, shows matches by source and jumps to them", async () => {
    backend.setOriginality({
      score: 18.5,
      words: 200,
      matched_words: 37,
      sentences: 9,
      probes: 4,
      sources_checked: 20,
      partial: false,
      matches: [{ sentence: "We measured teh field at and teh results were clear.", overlap: 0.82, source_id: "W1", excerpt: "we measured the field at low temperature" }],
      sources: [{ id: "W1", title: "Magnons at low temperature", year: 2021, url: "https://doi.org/10.1/m", doi: "10.1/m", sentences: 1, words: 37 }],
    });
    const { user } = await open();
    await user.click(screen.getByRole("button", { name: "AI" }));
    const sheet = screen.getByRole("dialog", { name: "AI writing assistant" });
    await user.click(within(sheet).getByRole("tab", { name: "Originality" }));
    expect(await within(sheet).findByText("No checks yet")).toBeInTheDocument();
    await user.click(within(sheet).getByRole("button", { name: /Run originality check/ }));
    expect(await within(sheet).findByText("Some overlap", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(within(sheet).getByText("18.5%")).toBeInTheDocument();
    expect(within(sheet).getByRole("link", { name: /Magnons at low temperature/ })).toHaveAttribute("href", "https://doi.org/10.1/m");
    expect(within(sheet).getByText(/82% of its wording/)).toBeInTheDocument();

    await user.click(within(sheet).getByRole("button", { name: "Show in document" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "AI writing assistant" })).not.toBeInTheDocument());
    const selection = view().state.selection.main;
    expect(view().state.sliceDoc(selection.from, selection.to)).toMatch(/^We measured teh field at \$T<T_c\$ and teh results/);
  });

  it("reports a clean document and keeps earlier checks", async () => {
    const { user } = await open();
    await user.click(screen.getByRole("button", { name: "AI" }));
    const sheet = screen.getByRole("dialog", { name: "AI writing assistant" });
    await user.click(within(sheet).getByRole("tab", { name: "Originality" }));
    await user.click(await within(sheet).findByRole("button", { name: /Run originality check/ }));
    expect(await within(sheet).findByText("No sentence matched a published title or abstract.", {}, { timeout: 5000 })).toBeInTheDocument();
    await user.click(within(sheet).getByRole("button", { name: /Check again/ }));
    expect(await within(sheet).findByRole("region", { name: "Earlier checks" }, { timeout: 5000 }).catch(() => within(sheet).getByLabelText("Earlier checks"))).toBeInTheDocument();
  });

  it("opens from the command palette", async () => {
    const { user } = await open();
    await user.keyboard("{Control>}k{/Control}");
    await user.type(await screen.findByRole("combobox"), "originality");
    await user.keyboard("{Enter}");
    const sheet = await screen.findByRole("dialog", { name: "AI writing assistant" });
    expect(within(sheet).getByRole("tab", { name: "Originality" })).toHaveAttribute("aria-selected", "true");
  });
});

describe("locate", () => {
  it("finds prose in LaTeX across markup and spacing", () => {
    const latex = "Intro. We \\emph{measured} the\nfield at $T$ today.";
    const at = locate(latex, "We measured the field at today.");
    expect(at && latex.slice(at[0], at[1])).toBe("We \\emph{measured} the\nfield at $T$ today");
    expect(locate(latex, "Nothing like this here at all")).toBeNull();
  });
});
