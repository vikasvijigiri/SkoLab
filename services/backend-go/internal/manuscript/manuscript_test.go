package manuscript

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var full = `\documentclass{article}
% \title{Commented out}
\title{Spin waves in thin films}
\author{A. Author \and B. Author}
\begin{document}
\maketitle
\begin{abstract}
` + fiftyWords + `
\end{abstract}
\section{Introduction}\label{sec:intro}
Magnons carry spin~\cite{kittel,bloch} and 50\% of the text, see Eq.~\eqref{eq:1}.
\begin{equation}\label{eq:1} E = \hbar \omega \end{equation}
\[ x^2 \]
\begin{figure}\includegraphics{fig1.pdf}\caption{A figure.}\end{figure}
\begin{table}\begin{tabular}{c} a \end{tabular}\end{table}
\section*{Conclusions}
We conclude, as \citet[p.~2]{bloch} did.
\begin{thebibliography}{2}
\bibitem{kittel} C. Kittel, Introduction to Solid State Physics.
\bibitem[Bloch(1930)]{bloch} F. Bloch.
\end{thebibliography}
\end{document}
`

var fiftyWords = strings.Repeat("word ", 50)

func check(t *testing.T, in Insights, id string) Check {
	t.Helper()
	for _, c := range in.Progress.Checks {
		if c.ID == id {
			return c
		}
	}
	t.Fatalf("no check %q", id)
	return Check{}
}

func TestAnalyzeAFullManuscript(t *testing.T) {
	in := Analyze(full)
	want := Stats{Sections: 2, Figures: 1, Tables: 1, Equations: 2, Citations: 2, References: 2}
	got := in.Stats
	got.Words = 0
	if got != want {
		t.Fatalf("stats = %+v, want %+v", in.Stats, want)
	}
	// Prose only: no keys, file names, math, caption or bibliography words.
	if in.Stats.Words < 15 || in.Stats.Words > 30 {
		t.Fatalf("words = %d, want the body prose only", in.Stats.Words)
	}
	for _, id := range []string{"title", "authors", "abstract", "introduction", "conclusion", "references", "citations"} {
		if !check(t, in, id).Done {
			t.Errorf("check %s not done", id)
		}
	}
	if check(t, in, "length").Done {
		t.Error("a short body is not full length")
	}
	// 7 of 8 checks plus a sliver of the length check.
	if in.Progress.Percent != 87 {
		t.Errorf("percent = %d, want 87", in.Progress.Percent)
	}
	if len(in.UnresolvedCitations) != 0 {
		t.Errorf("unresolved = %v", in.UnresolvedCitations)
	}
}

func TestAnalyzeAnEmptyDocument(t *testing.T) {
	for _, source := range []string{"", `\documentclass{article}\begin{document}\end{document}`, "{{{{", `\title`, `\title{`} {
		in := Analyze(source)
		if in.Progress.Percent != 0 || in.Stats != (Stats{}) {
			t.Errorf("%q: %+v", source, in)
		}
		if in.UnresolvedCitations == nil || len(in.Progress.Checks) != 8 {
			t.Errorf("%q: lists must be present for JSON", source)
		}
	}
}

func TestUnresolvedCitations(t *testing.T) {
	in := Analyze(`\begin{document}\cite{a, b}\cite{*c}\nocite{*}\begin{thebibliography}{1}\bibitem{a} A.\end{thebibliography}\end{document}`)
	if strings.Join(in.UnresolvedCitations, ",") != "b,c" {
		t.Fatalf("unresolved = %v", in.UnresolvedCitations)
	}
	if check(t, in, "citations").Done {
		t.Error("missing references must fail the citations check")
	}
}

func TestLengthGivesPartialCredit(t *testing.T) {
	half := `\begin{document}` + strings.Repeat("word ", TargetWords/2) + `\end{document}`
	if p := Analyze(half).Progress.Percent; p != 6 { // half of one check in eight
		t.Fatalf("percent = %d, want 6", p)
	}
	whole := `\begin{document}` + strings.Repeat("word ", TargetWords*2) + `\end{document}`
	if !check(t, Analyze(whole), "length").Done {
		t.Fatal("a long body is full length")
	}
}

func TestVerbAndCommentsAreNotMarkup(t *testing.T) {
	in := Analyze(`\begin{document}
Use \verb|\end{document}| and \verb+\section{x}+ freely. 100\% sure.
% \section{Hidden}
\section{Results}
\end{document}`)
	if in.Stats.Sections != 1 {
		t.Fatalf("sections = %d, want 1", in.Stats.Sections)
	}
	if in.Stats.Words < 6 {
		t.Fatalf("words = %d: text after \\verb must count", in.Stats.Words)
	}
}

func TestTitleIgnoresLookalikeCommands(t *testing.T) {
	in := Analyze(`\titlepage{x}\titlefont{y}\title[Short]{}`)
	if check(t, in, "title").Done {
		t.Fatal("an empty title is not a title")
	}
	if !check(t, Analyze(`\title[Short]{Long title}`), "title").Done {
		t.Fatal("a title with a short form counts")
	}
}

func TestEveryCatalogTemplateIsReadable(t *testing.T) {
	files, err := filepath.Glob("../templates/files/*.tex")
	if err != nil || len(files) == 0 {
		t.Fatalf("no templates: %v", err)
	}
	for _, f := range files {
		source, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		in := Analyze(string(source))
		if in.Progress.Percent < 0 || in.Progress.Percent > 100 {
			t.Errorf("%s: percent %d", f, in.Progress.Percent)
		}
		for _, key := range in.UnresolvedCitations {
			if strings.ContainsAny(key, `\ {}`) {
				t.Errorf("%s: %q is not a citation key", f, key)
			}
		}
	}
}

func FuzzAnalyze(f *testing.F) {
	f.Add(full)
	f.Add(`\verb`)
	f.Add(`\title{\}`)
	f.Fuzz(func(t *testing.T, source string) {
		in := Analyze(source)
		if in.Progress.Percent < 0 || in.Progress.Percent > 100 {
			t.Fatalf("percent %d", in.Progress.Percent)
		}
	})
}

// A full-size document is analysed on every save; it must stay cheap.
func BenchmarkAnalyzeLargestDocument(b *testing.B) {
	source := strings.Repeat(full, 100_000/len(full))
	for b.Loop() {
		Analyze(source)
	}
}
