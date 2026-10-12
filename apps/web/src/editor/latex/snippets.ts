/**
 * Ready-made blocks for slash commands and the command bar. Templates use
 * CodeMirror's snippet syntax: ${name} is a field Tab moves through, ${} the
 * final cursor position.
 */
export interface Snippet {
  /** What follows the slash: /figure. */
  name: string;
  title: string;
  detail: string;
  /** Other words it is found by. */
  aliases: string[];
  template: string;
}

export const SNIPPETS: readonly Snippet[] = [
  {
    name: "figure",
    title: "Figure",
    detail: "Image with caption and label",
    aliases: ["image", "picture", "graphic"],
    template: "\\begin{figure}[htbp]\n  \\centering\n  \\includegraphics[width=0.8\\linewidth]{${figures/file}}\n  \\caption{${Caption}}\n  \\label{fig:${name}}\n\\end{figure}\n${}",
  },
  {
    name: "subfig",
    title: "Figure, two panels",
    detail: "Side-by-side subfigures (subcaption)",
    aliases: ["subfigure", "panels"],
    template:
      "\\begin{figure}[htbp]\n  \\centering\n  \\begin{subfigure}{0.48\\linewidth}\n    \\includegraphics[width=\\linewidth]{${figures/left}}\n    \\caption{${Left}}\n  \\end{subfigure}\\hfill\n  \\begin{subfigure}{0.48\\linewidth}\n    \\includegraphics[width=\\linewidth]{${figures/right}}\n    \\caption{${Right}}\n  \\end{subfigure}\n  \\caption{${Caption}}\n  \\label{fig:${name}}\n\\end{figure}\n${}",
  },
  {
    name: "table",
    title: "Table",
    detail: "Three columns with booktabs rules",
    aliases: ["tabular", "grid"],
    template:
      "\\begin{table}[htbp]\n  \\centering\n  \\caption{${Caption}}\n  \\label{tab:${name}}\n  \\begin{tabular}{lcc}\n    \\toprule\n    ${Quantity} & ${Value} & ${Unit} \\\\\n    \\midrule\n    ${} &  &  \\\\\n    \\bottomrule\n  \\end{tabular}\n\\end{table}\n",
  },
  { name: "eq", title: "Equation", detail: "Numbered display equation", aliases: ["equation", "math", "formula"], template: "\\begin{equation}\n  ${E = mc^2}\n  \\label{eq:${name}}\n\\end{equation}\n${}" },
  { name: "align", title: "Aligned equations", detail: "Several lines lined up at &", aliases: ["multiline", "derivation"], template: "\\begin{align}\n  ${a} &= ${b} \\\\\n  &= ${c}\n\\end{align}\n${}" },
  { name: "inline", title: "Inline math", detail: "$…$ inside a sentence", aliases: ["dollar"], template: "$${x}$${}" },
  { name: "list", title: "Bulleted list", detail: "itemize", aliases: ["itemize", "bullets"], template: "\\begin{itemize}\n  \\item ${}\n\\end{itemize}\n" },
  { name: "numbered", title: "Numbered list", detail: "enumerate", aliases: ["enumerate", "steps"], template: "\\begin{enumerate}\n  \\item ${}\n\\end{enumerate}\n" },
  { name: "section", title: "Section", detail: "New section with a label", aliases: ["heading", "chapter"], template: "\\section{${Title}}\n\\label{sec:${name}}\n\n${}" },
  { name: "subsection", title: "Subsection", detail: "New subsection", aliases: ["subheading"], template: "\\subsection{${Title}}\n${}" },
  { name: "theorem", title: "Theorem and proof", detail: "theorem + proof (amsthm)", aliases: ["lemma", "proof"], template: "\\begin{theorem}\n  ${Statement}\n\\end{theorem}\n\\begin{proof}\n  ${}\n\\end{proof}\n" },
  { name: "cite", title: "Citation", detail: "Pick from your .bib", aliases: ["reference", "bib"], template: "\\cite{${}}" },
  { name: "ref", title: "Cross-reference", detail: "Refer to a label", aliases: ["link", "see"], template: "\\ref{${}}" },
  { name: "footnote", title: "Footnote", detail: "\\footnote{…}", aliases: ["note"], template: "\\footnote{${}}" },
  { name: "matrix", title: "Matrix", detail: "2×2 bmatrix", aliases: ["bmatrix", "array"], template: "\\begin{bmatrix}\n  ${a} & ${b} \\\\\n  ${c} & ${d}\n\\end{bmatrix}${}" },
  { name: "code", title: "Code listing", detail: "verbatim block", aliases: ["verbatim", "listing"], template: "\\begin{verbatim}\n${}\n\\end{verbatim}\n" },
  { name: "abstract", title: "Abstract", detail: "abstract environment", aliases: ["summary"], template: "\\begin{abstract}\n  ${}\n\\end{abstract}\n" },
  { name: "quote", title: "Quotation", detail: "quote environment", aliases: ["blockquote"], template: "\\begin{quote}\n  ${}\n\\end{quote}\n" },
];

/** The LaTeX a template inserts with its fields filled by their placeholder text. */
export function plainSnippet(template: string): string {
  return template.replace(/\$\{([^}]*)\}/g, "$1").replace(/\\([{}])/g, "$1");
}

/** A figure block for an image already in the project. */
export function figureFor(path: string): string {
  const base = path.split("/").pop() ?? path;
  const name = base.replace(/\.[^.]+$/, "").replace(/[^a-zA-Z0-9]+/g, "-").replace(/^-|-$/g, "").toLowerCase() || "figure";
  // Braces in a name would end the snippet's fields; the backslash keeps them literal.
  const graphic = path.replace(/\.(?:png|jpe?g|pdf|eps)$/i, "").replace(/[{}]/g, "\\$&");
  // "${Caption}" is a snippet field, so it is spelled out rather than interpolated.
  return `\\begin{figure}[htbp]\n  \\centering\n  \\includegraphics[width=0.8\\linewidth]{${graphic}}\n  \\caption{` + "${Caption}" + `}\n  \\label{fig:${name}}\n\\end{figure}\n`;
}
