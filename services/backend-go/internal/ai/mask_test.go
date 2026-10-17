package ai

import (
	"strings"
	"testing"
	"unicode/utf16"
)

func TestMaskProtectsEverythingButProse(t *testing.T) {
	src := `As shown by \citet{smith2020} and in Eq.~\eqref{eq:1}, the energy $E=mc^2$ is \textbf{conserved}. % TODO check
\begin{equation}\label{eq:1} a+b \end{equation}
See \url{https://x.org/a_b} and \( \alpha \) or \[ \beta \] and $$\gamma$$. Costs 5\% more, \emph{really}.`
	m := Mask(src)
	for _, protected := range []string{`\citet{smith2020}`, `\eqref{eq:1}`, `$E=mc^2$`, `\textbf`, `% TODO check`, `\begin{equation}`, `\url{https://x.org/a_b}`, `\( \alpha \)`, `\[ \beta \]`, `$$\gamma$$`, `\%`, `\emph`} {
		if strings.Contains(m.Text, protected) {
			t.Errorf("%q is exposed in %q", protected, m.Text)
		}
	}
	for _, kept := range []string{"As shown by", "the energy", "{conserved}", "Costs 5", "{really}"} {
		if !strings.Contains(m.Text, kept) {
			t.Errorf("%q is hidden: %q", kept, m.Text)
		}
	}
	if m.Unmask(m.Text) != src {
		t.Fatalf("round trip changed the text:\n%q\n%q", src, m.Unmask(m.Text))
	}
}

func TestMaskKeepsProseEnvironmentsAndOptionalArguments(t *testing.T) {
	m := Mask(`\begin{itemize}\item[a)] First point.\end{itemize}\section*[Short]{A long title}`)
	if !strings.Contains(m.Text, "First point.") || !strings.Contains(m.Text, "{A long title}") || strings.Contains(m.Text, "Short") || strings.Contains(m.Text, "a)") {
		t.Fatalf("%q", m.Text)
	}
}

func TestMaskSurvivesBrokenLaTeX(t *testing.T) {
	for _, src := range []string{`unclosed $math`, `\cite{open`, `\begin{equation} never ends`, `\`, `trailing \[`, `\textbf[`, `literal ⟦1⟧ bracket ⟧`, `\é accent`, "%no newline", `\(x`, `$$x$`} {
		m := Mask(src)
		if m.Unmask(m.Text) != src {
			t.Errorf("%q: round trip gave %q", src, m.Unmask(m.Text))
		}
		if _, ok := placeholders(m.Text); !ok {
			t.Errorf("%q: masked text %q has a stray bracket", src, m.Text)
		}
	}
}

func TestUnmaskLeavesUnknownPlaceholders(t *testing.T) {
	m := Mask(`a $x$ b`)
	if got := m.Unmask("⟦9⟧ and ⟦x⟧ and ⟦1⟧ and ⟦"); got != "⟦9⟧ and ⟦x⟧ and $x$ and ⟦" {
		t.Fatalf("%q", got)
	}
}

func TestPlaceholdersAndProse(t *testing.T) {
	cases := map[string]struct {
		want []int
		ok   bool
	}{
		"no placeholders":  {nil, true},
		"⟦1⟧ then ⟦2⟧":     {[]int{1, 2}, true},
		"⟦2⟧⟦1⟧":           {[]int{2, 1}, true},
		"stray ⟧ close":    {nil, false},
		"half ⟦3":          {nil, false},
		"⟦x⟧":              {nil, false},
		"⟦0⟧":              {nil, false},
		"⟦1⟧ then stray ⟦": {[]int{1}, false},
	}
	for s, want := range cases {
		got, ok := placeholders(s)
		if ok != want.ok || len(got) != len(want.want) {
			t.Errorf("%q: %v %v", s, got, ok)
		}
	}
	if prose("⟦1⟧ ⟦22⟧ .") != 0 || prose("Héllo ⟦1⟧") != 5 {
		t.Fatal("prose count")
	}
}

func FuzzMaskRoundTrips(f *testing.F) {
	for _, seed := range []string{`a $b$ \cite{c} d`, `\begin{align}x\end{align}`, `%c` + "\n" + `x`, `⟦1⟧`, `\[`, `{\bf x}`} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, src string) {
		m := Mask(src)
		if m.Unmask(m.Text) != src {
			t.Fatalf("round trip: %q -> %q -> %q", src, m.Text, m.Unmask(m.Text))
		}
		if _, ok := placeholders(m.Text); !ok {
			t.Fatalf("stray bracket in %q", m.Text)
		}
	})
}

func TestVerifyKeepsOnlySafeEdits(t *testing.T) {
	src := `The results \cite{x} was clear and $p<0.05$. We obsereved \textbf{large} gains.`
	m := Mask(src)
	// ⟦1⟧ = \cite{x}, ⟦2⟧ = $p<0.05$, ⟦3⟧ = \textbf
	edits := []Edit{
		{Original: "results ⟦1⟧ was", Replacement: "results ⟦1⟧ were", Category: "grammar", Reason: " Agreement "},
		{Original: "obsereved", Replacement: "observed", Category: "made-up"},
		{Original: "and ⟦2⟧", Replacement: "and p was small", Category: "clarity"}, // drops a placeholder
		{Original: "⟦3⟧{large}", Replacement: "⟦3⟧{large", Category: "clarity"},    // unbalances braces
		{Original: "not in the text", Replacement: "x"},                            // invented quote
		{Original: "results ⟦1⟧", Replacement: "findings ⟦1⟧"},                     // overlaps the first
		{Original: "gains", Replacement: "gains"},                                  // no change
		{Original: "gains", Replacement: strings.Repeat("huge ", 100)},             // too long
		{Original: "clear", Replacement: "⟦1⟧ clear"},                              // adds a placeholder
		{Original: "clear", Replacement: "clear ⟧"},                                // stray bracket
	}
	got, refused := Verify(m, edits)
	if len(got) != 2 {
		t.Fatalf("%+v", got)
	}
	first, second := got[0], got[1]
	if first.Original != `results \cite{x} was` || first.Replacement != `results \cite{x} were` || first.Reason != "Agreement" || first.Category != "grammar" || first.ID != "s1" {
		t.Fatalf("%+v", first)
	}
	if second.Original != "obsereved" || second.Category != "clarity" {
		t.Fatalf("%+v", second)
	}
	for _, s := range got {
		u := utf16.Encode([]rune(src))
		if string(utf16.Decode(u[s.Start:s.End])) != s.Original {
			t.Fatalf("offsets %d-%d do not hold %q", s.Start, s.End, s.Original)
		}
	}
	want := []string{refusedPlaceholders, refusedBraces, refusedNotFound, refusedOverlap, refusedUnchanged, refusedSize, refusedPlaceholders, refusedPlaceholders}
	if strings.Join(refused, ",") != strings.Join(want, ",") {
		t.Fatalf("refused %v", refused)
	}
}

func TestVerifyOffsetsCountUTF16(t *testing.T) {
	src := "Émigré 🧪 resülts was gööd."
	m := Mask(src)
	got, _ := Verify(m, []Edit{{Original: "was", Replacement: "were"}})
	u := utf16.Encode([]rune(src))
	if len(got) != 1 || string(utf16.Decode(u[got[0].Start:got[0].End])) != "was" {
		t.Fatalf("%+v", got)
	}
}

func TestVerifyPicksTheNextOccurrenceWhenTheFirstIsTaken(t *testing.T) {
	m := Mask("the cat and the cat")
	got, refused := Verify(m, []Edit{{Original: "the cat", Replacement: "a cat"}, {Original: "the cat", Replacement: "the dog"}})
	if len(got) != 2 || got[1].Start != 12 || len(refused) != 0 {
		t.Fatalf("%+v %v", got, refused)
	}
}

func TestBracesIgnoreEscapes(t *testing.T) {
	if braces(`\{ a } {`) != 0 || braces(`{{}`) != 1 {
		t.Fatal("braces")
	}
}
