import amsArticle from "./templates/ams-article.tex?raw";
import apsRevtex from "./templates/aps-revtex.tex?raw";
import beamerTalk from "./templates/beamer-talk.tex?raw";
import blankArticle from "./templates/blank-article.tex?raw";
import elsevierArticle from "./templates/elsevier-article.tex?raw";
import ieeeConference from "./templates/ieee-conference.tex?raw";
import ieeeJournal from "./templates/ieee-journal.tex?raw";
import moderncvCv from "./templates/moderncv-cv.tex?raw";
import tufteHandout from "./templates/tufte-handout.tex?raw";

export const CATEGORIES = ["Conference", "Journal", "Article", "Presentation", "CV"] as const;
export type Category = (typeof CATEGORIES)[number];

export interface Template {
  id: string;
  name: string;
  category: Category;
  description: string;
  /** Who wrote and maintains the upstream template. */
  author: string;
  license: string;
  /** Where the upstream template is published, or null for SkoLab's own. */
  sourceUrl: string | null;
  source: string;
}

/**
 * Official templates as published on CTAN by their maintainers, taken from
 * the TeX Live release the compiler runs (so class and template versions
 * match). Changes from upstream are listed in each file's header; every one
 * compiles in CI through the production image (services/qa/templates_compile.py).
 */
export const TEMPLATES: readonly Template[] = [
  {
    id: "blank",
    name: "Blank Article",
    category: "Article",
    description: "A clean article with title, abstract, sections, an equation and a bibliography. Start from scratch.",
    author: "SkoLab",
    license: "CC0",
    sourceUrl: null,
    source: blankArticle,
  },
  {
    id: "ieee-conference",
    name: "IEEE Conference Paper",
    category: "Conference",
    description: "The IEEEtran bare conference skeleton used for IEEE conferences: two columns, author blocks, abstract and references.",
    author: "Michael Shell / IEEE",
    license: "LPPL 1.3",
    sourceUrl: "https://ctan.org/pkg/ieeetran",
    source: ieeeConference,
  },
  {
    id: "ieee-journal",
    name: "IEEE Journal Article",
    category: "Journal",
    description: "The IEEEtran bare journal skeleton for IEEE Transactions and journals, with biographies and appendices.",
    author: "Michael Shell / IEEE",
    license: "LPPL 1.3",
    sourceUrl: "https://ctan.org/pkg/ieeetran",
    source: ieeeJournal,
  },
  {
    id: "elsevier-article",
    name: "Elsevier Journal Article",
    category: "Journal",
    description: "Elsevier's elsarticle template with numbered references, highlights, keywords, equations, a table and a figure.",
    author: "Elsevier Ltd",
    license: "LPPL 1.3",
    sourceUrl: "https://ctan.org/pkg/elsarticle",
    source: elsevierArticle,
  },
  {
    id: "aps-revtex",
    name: "APS Physical Review (REVTeX 4.2)",
    category: "Journal",
    description: "The American Physical Society's REVTeX 4.2 manuscript template for Physical Review journals.",
    author: "American Physical Society",
    license: "LPPL 1.3c",
    sourceUrl: "https://ctan.org/pkg/revtex",
    source: apsRevtex,
  },
  {
    id: "ams-article",
    name: "AMS Mathematics Article",
    category: "Article",
    description: "The American Mathematical Society's amsart template with theorem, lemma and definition environments.",
    author: "American Mathematical Society",
    license: "LPPL 1.3c",
    sourceUrl: "https://ctan.org/pkg/amscls",
    source: amsArticle,
  },
  {
    id: "tufte-handout",
    name: "Tufte Handout",
    category: "Article",
    description: "Edward Tufte's style with wide margins for sidenotes and margin figures, from the tufte-latex project.",
    author: "The Tufte-LaTeX Developers",
    license: "Apache 2.0",
    sourceUrl: "https://ctan.org/pkg/tufte-latex",
    source: tufteHandout,
  },
  {
    id: "beamer-talk",
    name: "Beamer Conference Talk",
    category: "Presentation",
    description: "Till Tantau's Beamer solution template for a 15 to 45 minute talk, with outline, sections and overlays.",
    author: "Till Tantau (Beamer)",
    license: "Free to use and modify",
    sourceUrl: "https://ctan.org/pkg/beamer",
    source: beamerTalk,
  },
  {
    id: "moderncv-cv",
    name: "Academic CV and Cover Letter",
    category: "CV",
    description: "The moderncv template: a CV with education, experience, skills and publications, plus a matching cover letter.",
    author: "Xavier Danaux and the moderncv maintainers",
    license: "LPPL 1.3c",
    sourceUrl: "https://ctan.org/pkg/moderncv",
    source: moderncvCv,
  },
];

export function templateById(id: string): Template | undefined {
  return TEMPLATES.find((template) => template.id === id);
}
