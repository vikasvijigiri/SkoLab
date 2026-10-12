package templates

// catalog is the editor's template list in display order: two or three
// standard journal templates for each domain. Adding one means adding its
// file under files/ (with a header saying what changed from upstream) and
// an entry here; CI then compiles it through the production image.
var catalog = []Template{
	// ── Physics ──────────────────────────────────────────────────────────────
	{
		ID: "aps-physical-review", file: "aps-physical-review.tex", Domain: "physics",
		Name:        "APS Physical Review (REVTeX 4.2)",
		Journals:    "Physical Review Letters, Physical Review A–E, X, Applied, Research",
		Publisher:   "American Physical Society",
		Description: "The APS manuscript template for the Physical Review journals. Set as a PRL preprint; switch the journal and layout with the class options.",
		Class:       "revtex4-2", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/revtex",
	},
	{
		ID: "aip-applied-physics", file: "aip-applied-physics.tex", Domain: "physics",
		Name:        "AIP Journal of Applied Physics (REVTeX 4.2)",
		Journals:    "Journal of Applied Physics, Applied Physics Letters (option apl)",
		Publisher:   "AIP Publishing",
		Description: "AIP's REVTeX 4.2 template with the Journal of Applied Physics substyle.",
		Class:       "revtex4-2", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/revtex",
	},
	{
		ID: "quantum", file: "quantum.tex", Domain: "physics",
		Name:        "Quantum",
		Journals:    "Quantum, the open-access journal for quantum science",
		Publisher:   "Verein zur Förderung des Open Access Publizierens in den Quantenwissenschaften",
		Description: "The journal's own quantumarticle template, two columns, with figures, theorems, appendices and a DOI-linked bibliography.",
		Class:       "quantumarticle", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/quantumarticle",
	},
	// ── Chemistry ────────────────────────────────────────────────────────────
	{
		ID: "acs-jacs", file: "acs-jacs.tex", Domain: "chemistry",
		Name:        "ACS Journal of the American Chemical Society (achemso)",
		Journals:    "JACS and every ACS journal (journal=<code>)",
		Publisher:   "American Chemical Society",
		Description: "The achemso model paper for ACS journals: TOC graphic, chemical formulae, schemes, supporting information and ACS-style references.",
		Class:       "achemso", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/achemso",
	},
	{
		ID: "aip-chemical-physics", file: "aip-chemical-physics.tex", Domain: "chemistry",
		Name:        "AIP The Journal of Chemical Physics (REVTeX 4.2)",
		Journals:    "The Journal of Chemical Physics",
		Publisher:   "AIP Publishing",
		Description: "AIP's REVTeX 4.2 template with The Journal of Chemical Physics substyle.",
		Class:       "revtex4-2", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/revtex",
	},
	{
		ID: "elsevier-cas-sc", file: "elsevier-cas-sc.tex", Domain: "chemistry",
		Name:        "Elsevier journal, single column (CAS)",
		Journals:    "Elsevier journals that use the Complex Article Service layout",
		Publisher:   "Elsevier",
		Description: "Elsevier's CAS template in one column: highlights, graphical abstract, keywords, CRediT authorship and author-year references.",
		Class:       "cas-sc", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/els-cas-templates",
	},
	// ── Mathematics ──────────────────────────────────────────────────────────
	{
		ID: "ams-article", file: "ams-article.tex", Domain: "mathematics",
		Name:        "AMS journal article (amsart)",
		Journals:    "Journal of the AMS, Transactions and Proceedings of the AMS, and most mathematics journals",
		Publisher:   "American Mathematical Society",
		Description: "The AMS amsart template with theorem, lemma and definition environments and MSC subject classes.",
		Class:       "amsart", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/amscls",
	},
	{
		ID: "elsevier-article", file: "elsevier-article.tex", Domain: "mathematics",
		Name:        "Elsevier journal article (elsarticle)",
		Journals:    "Elsevier mathematics journals, e.g. Journal of Algebra, Advances in Mathematics",
		Publisher:   "Elsevier",
		Description: "Elsevier's elsarticle template with numbered references, keywords, equations, a table and a figure.",
		Class:       "elsarticle", License: "LPPL 1.3", SourceURL: "https://ctan.org/pkg/elsarticle",
	},
	// ── Biology ──────────────────────────────────────────────────────────────
	{
		ID: "oup-journal", file: "oup-journal.tex", Domain: "biology",
		Name:        "Oxford University Press journal",
		Journals:    "OUP journals, e.g. Bioinformatics, Nucleic Acids Research",
		Publisher:   "Oxford University Press",
		Description: "OUP's authoring template for its journals, with Contemporary, Modern and Traditional layouts, key points, figures, tables and references.",
		Class:       "oup-authoring-template", License: "LPPL 1.2", SourceURL: "https://ctan.org/pkg/oup-authoring-template",
	},
	{
		ID: "nature", file: "nature.tex", Domain: "biology",
		Name:        "Nature letter or article",
		Journals:    "Nature",
		Publisher:   "Community class by Peter Czoschke (not by Springer Nature)",
		Description: "A preprint in Nature's manuscript order: bold opening paragraph, methods, references, addendum and figure legends.",
		Class:       "nature", License: "LPPL 1.0+", SourceURL: "https://ctan.org/pkg/nature",
	},
	{
		ID: "elsevier-cas-dc", file: "elsevier-cas-dc.tex", Domain: "biology",
		Name:        "Elsevier journal, double column (CAS)",
		Journals:    "Elsevier journals that use the Complex Article Service layout",
		Publisher:   "Elsevier",
		Description: "Elsevier's CAS template in two columns: highlights, graphical abstract, keywords, CRediT authorship and author-year references.",
		Class:       "cas-dc", License: "LPPL 1.3c", SourceURL: "https://ctan.org/pkg/els-cas-templates",
	},
}
