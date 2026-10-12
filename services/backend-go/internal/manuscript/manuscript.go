// Package manuscript reads a LaTeX manuscript the way a co-author skimming
// it would: how long it is, what it contains (sections, figures, tables,
// equations, citations, references) and which parts a submission needs are
// still missing. Progress is the share of those parts in place.
//
// It is a heuristic over the source text, not a TeX engine: macros are not
// expanded and \input files are not followed (the compiler takes one file).
// It never fails; anything it cannot recognise simply does not count.
package manuscript

import (
	"math"
	"regexp"
	"sort"
	"strings"
	"unicode"
)

// TargetWords is the body length counted as "full length": a short research
// article (a Physical Review Letter is about 3,750 words, most journals'
// regular articles are longer). Shorter bodies get partial credit.
const TargetWords = 2500

// MinAbstractWords is the length below which an abstract still reads as a
// placeholder.
const MinAbstractWords = 50

type Stats struct {
	Words      int `json:"words"`
	Sections   int `json:"sections"`
	Figures    int `json:"figures"`
	Tables     int `json:"tables"`
	Equations  int `json:"equations"`
	Citations  int `json:"citations"`  // distinct keys cited
	References int `json:"references"` // \bibitem entries
}

type Check struct {
	ID    string `json:"id"`
	Label string `json:"label"`
	Done  bool   `json:"done"`
}

type Progress struct {
	Percent int     `json:"percent"`
	Checks  []Check `json:"checks"`
}

type Insights struct {
	Stats    Stats    `json:"stats"`
	Progress Progress `json:"progress"`
	// UnresolvedCitations are cited keys with no matching \bibitem (sorted, at most 20).
	UnresolvedCitations []string `json:"unresolved_citations"`
}

var (
	sectionRe   = regexp.MustCompile(`\\section\*?\s*(?:\[[^\]]*\])?\s*\{`)
	figureRe    = regexp.MustCompile(`\\begin\{(?:figure|wrapfigure|SCfigure)\*?\}`)
	tableRe     = regexp.MustCompile(`\\begin\{table\*?\}`)
	equationRe  = regexp.MustCompile(`\\begin\{(?:equation|align|gather|multline|eqnarray|flalign)\*?\}|\\\[`)
	citeRe      = regexp.MustCompile(`\\(?:cite|citep|citet|citealp|citealt|citeauthor|citeyear|citeyearpar|citenum|parencite|textcite|autocite|footcite|supercite|Cite|Citep|Citet)\*?\s*(?:\[[^\]]*\]\s*){0,2}\{([^}]*)\}`)
	bibitemRe   = regexp.MustCompile(`\\bibitem\s*(?:\[[^\]]*\])?\s*\{([^}]*)\}`)
	commandRe   = regexp.MustCompile(`\\[a-zA-Z@]+\*?|\\.`)
	envMathRe   = regexp.MustCompile(`(?s)\\begin\{(equation|align|gather|multline|eqnarray|flalign|figure|table|tabular|thebibliography|verbatim|lstlisting)(\*?)\}.*?\\end\{(?:equation|align|gather|multline|eqnarray|flalign|figure|table|tabular|thebibliography|verbatim|lstlisting)\*?\}`)
	displayRe   = regexp.MustCompile(`(?s)\\\[.*?\\\]|\$\$.*?\$\$`)
	inlineRe    = regexp.MustCompile(`\$[^$]*\$`)
	abstractEnv = regexp.MustCompile(`(?s)\\begin\{abstract\}(.*?)\\end\{abstract\}`)
	wordRe      = regexp.MustCompile(`[\p{L}\p{N}][\p{L}\p{N}'’-]*`)
	// Commands whose argument is a key, a path or metadata rather than prose.
	nonProseRe = regexp.MustCompile(`\\(?:label|ref|eqref|autoref|cref|Cref|pageref|includegraphics|url|href|bibliographystyle|bibliography|addbibresource|usepackage|affiliation|altaffiliation|address|email|thanks|date|pacs|keywords|title|author|documentclass|input|include)\*?\s*(?:\[[^\]]*\]\s*)?\{[^{}]*\}`)
)

// Analyze reads source; it is safe on any input up to the document size limit.
func Analyze(source string) Insights {
	text := stripVerb(stripComments(source))
	body := between(text, `\begin{document}`, `\end{document}`)

	sections := sectionTitles(body)
	cited := citedKeys(body)
	bibitems := map[string]bool{}
	for _, m := range bibitemRe.FindAllStringSubmatch(body, -1) {
		if key := strings.TrimSpace(m[1]); key != "" {
			bibitems[key] = true
		}
	}
	var unresolved []string
	for key := range cited {
		if !bibitems[key] {
			unresolved = append(unresolved, key)
		}
	}
	sort.Strings(unresolved)
	if len(unresolved) > 20 {
		unresolved = unresolved[:20]
	}
	if unresolved == nil {
		unresolved = []string{}
	}

	stats := Stats{
		Words:      countWords(bodyProse(body)),
		Sections:   len(sections),
		Figures:    len(figureRe.FindAllStringIndex(body, -1)),
		Tables:     len(tableRe.FindAllStringIndex(body, -1)),
		Equations:  len(equationRe.FindAllStringIndex(body, -1)),
		Citations:  len(cited),
		References: len(bibitems),
	}

	hasSection := func(words ...string) bool {
		for _, title := range sections {
			for _, w := range words {
				if strings.Contains(title, w) {
					return true
				}
			}
		}
		return false
	}
	title := plain(argument(text, `\title`))
	authors := plain(argument(text, `\author`))
	abstract := abstractText(text)

	checks := []Check{
		{ID: "title", Label: "Title", Done: title != ""},
		{ID: "authors", Label: "Authors", Done: authors != ""},
		{ID: "abstract", Label: "Abstract of at least 50 words", Done: countWords(abstract) >= MinAbstractWords},
		{ID: "introduction", Label: "Introduction section", Done: hasSection("intro", "background", "motivation")},
		{ID: "conclusion", Label: "Conclusion or discussion section", Done: hasSection("conclu", "discussion", "summary", "outlook")},
		{ID: "references", Label: "Reference list", Done: stats.References > 0},
		{ID: "citations", Label: "Every citation has a reference", Done: stats.Citations > 0 && len(unresolved) == 0},
		{ID: "length", Label: "Full length (2,500 words)", Done: stats.Words >= TargetWords},
	}
	score := 0.0
	for _, check := range checks {
		switch {
		case check.Done:
			score++
		case check.ID == "length":
			score += float64(stats.Words) / TargetWords
		}
	}
	percent := int(math.Floor(score / float64(len(checks)) * 100))

	return Insights{
		Stats:               stats,
		Progress:            Progress{Percent: percent, Checks: checks},
		UnresolvedCitations: unresolved,
	}
}

// stripComments drops everything from an unescaped % to the end of its line.
func stripComments(s string) string {
	var out strings.Builder
	out.Grow(len(s))
	for _, line := range strings.SplitAfter(s, "\n") {
		cut := len(line)
		for i := 0; i < len(line); i++ {
			if line[i] == '\\' {
				i++ // skip the escaped character, so \% stays
				continue
			}
			if line[i] == '%' {
				cut = i
				break
			}
		}
		out.WriteString(line[:cut])
		if cut < len(line) && strings.HasSuffix(line, "\n") {
			out.WriteByte('\n')
		}
	}
	return out.String()
}

// stripVerb blanks \verb|...| so the code it quotes (often \end{document}) is not read as markup.
func stripVerb(s string) string {
	var out strings.Builder
	out.Grow(len(s))
	for {
		i := strings.Index(s, `\verb`)
		if i < 0 {
			out.WriteString(s)
			return out.String()
		}
		out.WriteString(s[:i])
		rest := strings.TrimPrefix(s[i+len(`\verb`):], "*")
		if rest == "" || unicode.IsLetter(rune(rest[0])) || rest[0] == ' ' || rest[0] == '\n' {
			out.WriteString(`\verb`) // \verbatim or a stray \verb: leave it
			s = s[i+len(`\verb`):]
			continue
		}
		delim := rest[0]
		end := strings.IndexByte(rest[1:], delim)
		if end < 0 || strings.IndexByte(rest[1:end+1], '\n') >= 0 {
			out.WriteString(`\verb`)
			s = s[i+len(`\verb`):]
			continue
		}
		out.WriteString(" code ")
		s = rest[end+2:]
	}
}

// between returns the text between the first start and the last end, or all
// of s when start is absent.
func between(s, start, end string) string {
	i := strings.Index(s, start)
	if i < 0 {
		return s
	}
	s = s[i+len(start):]
	if j := strings.LastIndex(s, end); j >= 0 {
		return s[:j]
	}
	return s
}

// argument returns the brace-balanced mandatory argument of the first use of
// command (skipping an optional [..] argument), or "" when there is none.
func argument(s, command string) string {
	from := 0
	for {
		i := strings.Index(s[from:], command)
		if i < 0 {
			return ""
		}
		i += from + len(command)
		// \title must not match \titlepage or \titlefont.
		if i < len(s) && (unicode.IsLetter(rune(s[i])) || s[i] == '@') {
			from = i
			continue
		}
		rest := strings.TrimLeft(s[i:], " \t\n*")
		if strings.HasPrefix(rest, "[") {
			if j := strings.Index(rest, "]"); j >= 0 {
				rest = strings.TrimLeft(rest[j+1:], " \t\n")
			}
		}
		if !strings.HasPrefix(rest, "{") {
			from = i
			continue
		}
		return balanced(rest)
	}
}

// balanced returns the contents of the {...} group s starts with.
func balanced(s string) string {
	depth := 0
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '\\':
			i++
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return s[1:i]
			}
		}
	}
	return strings.TrimPrefix(s, "{")
}

func sectionTitles(body string) []string {
	var titles []string
	for _, loc := range sectionRe.FindAllStringIndex(body, -1) {
		titles = append(titles, strings.ToLower(plain(balanced(body[loc[1]-1:]))))
	}
	return titles
}

func citedKeys(body string) map[string]bool {
	keys := map[string]bool{}
	for _, m := range citeRe.FindAllStringSubmatch(body, -1) {
		for _, key := range strings.Split(m[1], ",") {
			// achemso's \cite{a,*b} merges b into a's entry; the key is still b.
			if key = strings.TrimPrefix(strings.TrimSpace(key), "*"); key != "" {
				keys[key] = true
			}
		}
	}
	return keys
}

func abstractText(text string) string {
	if m := abstractEnv.FindStringSubmatch(text); m != nil {
		return plain(m[1])
	}
	return plain(argument(text, `\abstract`))
}

// bodyProse is the running text of the body: no math, floats, tables or bibliography.
func bodyProse(body string) string {
	body = abstractEnv.ReplaceAllString(body, " ")
	body = envMathRe.ReplaceAllString(body, " ")
	body = displayRe.ReplaceAllString(body, " ")
	body = inlineRe.ReplaceAllString(body, " x ")
	body = nonProseRe.ReplaceAllString(body, " ")
	return plain(body)
}

// plain drops commands and grouping, leaving the words a reader sees.
func plain(s string) string {
	s = citeRe.ReplaceAllString(s, " ")
	s = commandRe.ReplaceAllString(s, " ")
	s = strings.NewReplacer("{", " ", "}", " ", "~", " ", "&", " ").Replace(s)
	return strings.Join(strings.Fields(s), " ")
}

func countWords(s string) int {
	return len(wordRe.FindAllStringIndex(s, -1))
}
