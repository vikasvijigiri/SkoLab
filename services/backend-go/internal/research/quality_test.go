package research

import (
	"reflect"
	"testing"
)

func TestAnalyzeQualityReportsUnresolvedUntrackedAndUnusedKeys(t *testing.T) {
	source := `\section{Results}
% \cite{commented}
We cite \citep[see][p. 4]{alpha,beta} and \textcite{gamma}.
Escaped \% is text.`
	bib := []string{`@article{alpha, title={A}}
@book{gamma, title={G}}
@article{unused, title={U}}`}
	got := AnalyzeQuality(source, bib, []Item{{CitationKey: "alpha"}})
	if !reflect.DeepEqual(got.CitedKeys, []string{"alpha", "beta", "gamma"}) {
		t.Fatalf("cited keys: %#v", got.CitedKeys)
	}
	if !reflect.DeepEqual(got.UnresolvedKeys, []string{"beta"}) {
		t.Fatalf("unresolved: %#v", got.UnresolvedKeys)
	}
	if !reflect.DeepEqual(got.CitationsWithoutEvidence, []string{"beta", "gamma"}) {
		t.Fatalf("without evidence: %#v", got.CitationsWithoutEvidence)
	}
	if !reflect.DeepEqual(got.UnusedReferences, []string{"unused"}) {
		t.Fatalf("unused: %#v", got.UnusedReferences)
	}
}

func TestAnalyzeQualityDeduplicatesAndReturnsEmptyArrays(t *testing.T) {
	got := AnalyzeQuality(`\cite{x,x}`, []string{`@article{x, title={X}}`}, []Item{{CitationKey: "x"}})
	if !reflect.DeepEqual(got.CitedKeys, []string{"x"}) || len(got.UnresolvedKeys) != 0 || len(got.CitationsWithoutEvidence) != 0 || len(got.UnusedReferences) != 0 {
		t.Fatalf("unexpected result: %#v", got)
	}
}

func TestAnalyzeQualityHonorsNociteAllAndIgnoresBibTeXDeclarations(t *testing.T) {
	got := AnalyzeQuality(`\nocite{*}`, []string{`@string{journal = {Journal Name}}
@article{real, title={A}}`}, nil)
	if !reflect.DeepEqual(got.CitedKeys, []string{"real"}) || len(got.UnresolvedKeys) != 0 || len(got.UnusedReferences) != 0 {
		t.Fatalf("unexpected result: %#v", got)
	}
}
