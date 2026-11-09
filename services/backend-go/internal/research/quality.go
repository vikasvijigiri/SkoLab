package research

import (
	"regexp"
	"sort"
	"strings"
)

var (
	citationCommand = regexp.MustCompile(`\\(?:cite|citep|citet|citealp|citealt|citeauthor|citeyear|autocite|parencite|textcite|smartcite|footcite|nocite|Cite|Parencite|Textcite)\*?(?:\[[^\]]*\]){0,2}\s*\{([^}]*)\}`)
	bibEntry        = regexp.MustCompile(`(?is)@(\w+)\s*[({]\s*([^,\s]+)\s*,`)
)

// AnalyzeQuality checks citation keys against project BibTeX files and the
// author-maintained evidence matrix. It deliberately reports coverage, not
// semantic support or paper quality.
func AnalyzeQuality(source string, bibliography []string, items []Item) Quality {
	cited := make(map[string]struct{})
	nociteAll := false
	for _, match := range citationCommand.FindAllStringSubmatch(stripComments(source), -1) {
		for _, key := range strings.Split(match[1], ",") {
			if key = strings.TrimSpace(key); key == "*" {
				nociteAll = true
			} else if key != "" {
				cited[key] = struct{}{}
			}
		}
	}
	bib := make(map[string]struct{})
	for _, content := range bibliography {
		for _, match := range bibEntry.FindAllStringSubmatch(stripComments(content), -1) {
			if key := strings.TrimSpace(match[2]); key != "" && !strings.EqualFold(match[1], "string") && !strings.EqualFold(match[1], "comment") && !strings.EqualFold(match[1], "preamble") {
				bib[key] = struct{}{}
			}
		}
	}
	if nociteAll {
		for key := range bib {
			cited[key] = struct{}{}
		}
	}
	evidence := make(map[string]struct{})
	for _, item := range items {
		if key := strings.TrimSpace(item.CitationKey); key != "" {
			evidence[key] = struct{}{}
		}
	}
	q := Quality{CitedKeys: sortedKeys(cited), BibliographyKeys: sortedKeys(bib), UnresolvedKeys: []string{}, CitationsWithoutEvidence: []string{}, UnusedReferences: []string{}}
	for key := range cited {
		if _, ok := bib[key]; !ok {
			q.UnresolvedKeys = append(q.UnresolvedKeys, key)
		}
		if _, ok := evidence[key]; !ok {
			q.CitationsWithoutEvidence = append(q.CitationsWithoutEvidence, key)
		}
	}
	for key := range bib {
		if _, ok := cited[key]; !ok {
			q.UnusedReferences = append(q.UnusedReferences, key)
		}
	}
	sort.Strings(q.UnresolvedKeys)
	sort.Strings(q.CitationsWithoutEvidence)
	sort.Strings(q.UnusedReferences)
	return q
}

func sortedKeys(values map[string]struct{}) []string {
	out := make([]string, 0, len(values))
	for key := range values {
		out = append(out, key)
	}
	sort.Strings(out)
	return out
}

// stripComments removes LaTeX comments while preserving escaped percent signs.
func stripComments(source string) string {
	var out strings.Builder
	for _, line := range strings.Split(source, "\n") {
		cut := len(line)
		for i := 0; i < len(line); i++ {
			if line[i] != '%' {
				continue
			}
			slashes := 0
			for j := i - 1; j >= 0 && line[j] == '\\'; j-- {
				slashes++
			}
			if slashes%2 == 0 {
				cut = i
				break
			}
		}
		out.WriteString(line[:cut])
		out.WriteByte('\n')
	}
	return out.String()
}
