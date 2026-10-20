//go:build live

package ai

import (
	"context"
	"fmt"
	"net/http"
	"testing"
	"time"
)

// A one-off probe against the real OpenAlex (go test -tags live -run Live).
func TestLiveOriginality(t *testing.T) {
	copied := "Proteins are essential to life, and understanding their structure can facilitate a mechanistic understanding of their function. Structural coverage is bottlenecked by the months to years of painstaking effort required to determine a single protein structure. Accurate computational approaches are needed to address this gap and to enable large-scale structural bioinformatics."
	edited := "Proteins are vital to life, and knowing their structure can facilitate a mechanistic understanding of how they function. Structural coverage is limited by the months to years of painstaking work needed to determine one protein structure. Accurate computational methods are needed to close this gap and to enable large-scale structural bioinformatics."
	original := "Our group built a small optical bench from salvaged parts during the spring semester and measured its drift every morning for three weeks. The basement room was unheated, so we logged the temperature beside each reading. The drift tracked the temperature closely, which suggests a simple correction for future runs."
	sameField := "We trained a compact graph network on twenty thousand experimentally solved protein structures from our own crystallography pipeline. Its predicted residue contacts agreed with the deposited coordinates for most small soluble domains we tested. Membrane proteins remained hard, and we discuss why our sampling of their conformations was too sparse."
	client := &http.Client{Timeout: 15 * time.Second}
	search := func(ctx context.Context, q string) ([]Work, error) {
		works, err := OpenAlex{Client: client}.Search(ctx, q)
		t.Logf("  search %q -> %d works", q, len(works))
		return works, err
	}
	for _, c := range []struct{ name, text string }{{"copied abstract", copied}, {"lightly edited", edited}, {"original prose", original}, {"original, same field", sameField}} {
		r, err := Check(context.Background(), `\begin{document}`+c.text+`\end{document}`, search, 4)
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		t.Logf("%s: score %.1f%% (%d of %d words), %d searches, %d works compared", c.name, r.Score, r.MatchedWords, r.Words, r.Probes, r.SourcesChecked)
		for _, m := range r.Matches {
			title := ""
			for _, s := range r.Sources {
				if s.ID == m.SourceID {
					title = fmt.Sprintf("%s (%d)", s.Title, s.Year)
				}
			}
			t.Logf("   match %.0f%%: %.70q -> %s", m.Overlap*100, m.Sentence, title)
		}
	}
}

// Every configured source together, as production runs them (go test -tags
// live -run LiveSources). Keys come from the environment (OPENALEX_API_KEY,
// TAVILY_API_KEY, SEMANTIC_SCHOLAR_API_KEY, CORE_API_KEY); budgets are not
// charged.
func TestLiveSources(t *testing.T) {
	cases := []struct{ name, text string }{
		// Wikipedia, "Photoelectric effect": only the web has it.
		{"web page", "The photoelectric effect is the emission of electrons from a material caused by electromagnetic radiation such as ultraviolet light. Electrons emitted in this manner are called photoelectrons. The phenomenon is studied in condensed matter physics, solid state, and quantum chemistry to draw inferences about the properties of atoms, molecules and solids."},
		// AlphaFold (Nature 2021, open access): a sentence of the main text, not the abstract.
		{"full text", "The AlphaFold network directly predicts the 3D coordinates of all heavy atoms for a given protein using the primary amino acid sequence and aligned sequences of homologues as inputs. We then describe the details of the network in the sections that follow, and we report on its accuracy for proteins that were withheld."},
		// Attention Is All You Need (arXiv 1706.03762), the abstract.
		{"preprint abstract", "The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. The best performing models also connect the encoder and decoder through an attention mechanism. We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely."},
		{"original prose", "Our group built a small optical bench from salvaged parts during the spring semester and measured its drift every morning for three weeks. The basement room was unheated, so we logged the temperature beside each reading. The drift tracked the temperature closely, which suggests a simple correction for future runs."},
	}
	cfg := FromEnv()
	providers := Sources(cfg, &http.Client{Timeout: 30 * time.Second})
	for _, c := range cases {
		f := NewFanout(nil, providers)
		started := time.Now()
		r, err := Check(context.Background(), `\begin{document}`+c.text+`\end{document}`, f.Search, 4)
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		t.Logf("%s: score %.1f%% %v in %v", c.name, r.Score, r.ByDatabase, time.Since(started).Round(time.Second))
		for _, cov := range f.Coverage() {
			t.Logf("   %-17s %-12s searches %d, works %d", cov.Source, cov.Status, cov.Searches, cov.Works)
		}
		for _, s := range r.Sources {
			t.Logf("   source [%s] %.70s (%s)", s.Database, s.Title, s.URL)
		}
	}
}
