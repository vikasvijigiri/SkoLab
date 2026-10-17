package ai

import (
	"strconv"
	"strings"
)

// Masked is LaTeX with everything a writing model must not touch replaced
// by numbered placeholders: ⟦1⟧, ⟦2⟧, ... Prose and the braces that hold
// it stay, so "\textbf{key result}" becomes "⟦1⟧{key result}".
type Masked struct {
	Text  string
	spans []string // spans[i] is what ⟦i+1⟧ stands for
}

const (
	phOpen  = "⟦"
	phClose = "⟧"
)

// Environments whose whole body is not prose: math, code and drawings.
var opaqueEnvironments = map[string]bool{
	"equation": true, "align": true, "gather": true, "multline": true, "eqnarray": true, "flalign": true, "alignat": true,
	"displaymath": true, "math": true, "split": true, "subequations": true,
	"verbatim": true, "lstlisting": true, "minted": true, "tikzpicture": true, "comment": true,
}

// Commands whose arguments are keys, paths or addresses, never prose.
var opaqueArguments = map[string]bool{
	"cite": true, "citep": true, "citet": true, "citealp": true, "citeauthor": true, "citeyear": true, "parencite": true, "textcite": true, "autocite": true, "nocite": true,
	"ref": true, "eqref": true, "autoref": true, "cref": true, "Cref": true, "pageref": true, "label": true,
	"url": true, "href": true, "includegraphics": true, "input": true, "include": true,
	"bibliography": true, "bibliographystyle": true, "usepackage": true, "documentclass": true,
	"begin": true, "end": true, "hspace": true, "vspace": true, "SI": true, "si": true,
}

// Mask protects src. It never fails: anything it cannot parse (an unclosed
// group, a stray $) is protected to the end of the text rather than exposed.
func Mask(src string) Masked {
	var b strings.Builder
	m := Masked{}
	protect := func(span string) {
		m.spans = append(m.spans, span)
		b.WriteString(phOpen)
		b.WriteString(strconv.Itoa(len(m.spans)))
		b.WriteString(phClose)
	}
	for i := 0; i < len(src); {
		rest := src[i:]
		switch {
		case strings.HasPrefix(rest, phOpen) || strings.HasPrefix(rest, phClose):
			// A literal placeholder bracket in the source is protected too, so
			// placeholders stay unambiguous.
			n := len(phOpen)
			protect(rest[:n])
			i += n
		case rest[0] == '%':
			end := strings.IndexByte(rest, '\n')
			if end < 0 {
				end = len(rest)
			}
			protect(rest[:end])
			i += end
		case strings.HasPrefix(rest, "$$"):
			n := closing(rest, 2, "$$")
			protect(rest[:n])
			i += n
		case rest[0] == '$':
			n := closing(rest, 1, "$")
			protect(rest[:n])
			i += n
		case strings.HasPrefix(rest, `\(`):
			n := closing(rest, 2, `\)`)
			protect(rest[:n])
			i += n
		case strings.HasPrefix(rest, `\[`):
			n := closing(rest, 2, `\]`)
			protect(rest[:n])
			i += n
		case rest[0] == '\\':
			n := command(rest)
			protect(rest[:n])
			i += n
		default:
			b.WriteByte(rest[0])
			i++
		}
	}
	m.Text = b.String()
	return m
}

// closing is the length of rest up to and including the delimiter that
// closes a span opened by its first skip bytes, skipping escaped characters;
// the whole of rest when it never closes.
func closing(rest string, skip int, delimiter string) int {
	for j := skip; j < len(rest); j++ {
		if rest[j] == '\\' {
			if strings.HasPrefix(rest[j:], delimiter) {
				return j + len(delimiter)
			}
			j++ // an escaped character, such as \$
			continue
		}
		if strings.HasPrefix(rest[j:], delimiter) {
			return j + len(delimiter)
		}
	}
	return len(rest)
}

// command is the length of the control sequence at the start of rest, with
// what must be protected along with it: optional [..] arguments always;
// brace arguments too for commands whose arguments are not prose; and a
// whole opaque environment from \begin{env} to its \end{env}.
func command(rest string) int {
	j := 1
	for j < len(rest) && isLetter(rest[j]) {
		j++
	}
	if j == 1 {
		// A control symbol: \%, \&, \\, \,
		if len(rest) > 1 {
			j = 2
			if rest[1] >= 0x80 { // keep a multi-byte character whole
				for j < len(rest) && rest[j]&0xC0 == 0x80 {
					j++
				}
			}
		}
		return j
	}
	name := rest[1:j]
	if j < len(rest) && rest[j] == '*' {
		j++
	}
	if name == "begin" {
		if env, after, ok := group(rest, j, '{', '}'); ok {
			envName := strings.TrimSuffix(strings.TrimSpace(env), "*")
			if opaqueEnvironments[envName] {
				end := `\end{` + strings.TrimSpace(env) + `}`
				if k := strings.Index(rest[after:], end); k >= 0 {
					return after + k + len(end)
				}
				return len(rest)
			}
			return after
		}
	}
	for {
		k := skipSpaces(rest, j)
		if k < len(rest) && rest[k] == '[' {
			if _, after, ok := group(rest, k, '[', ']'); ok {
				j = after
				continue
			}
			return len(rest)
		}
		if opaqueArguments[name] && k < len(rest) && rest[k] == '{' {
			if _, after, ok := group(rest, k, '{', '}'); ok {
				j = after
				continue
			}
			return len(rest)
		}
		return j
	}
}

// group reads a balanced group opening at rest[at], answering its inside
// and the index just after it.
func group(rest string, at int, opener, closer byte) (string, int, bool) {
	if at >= len(rest) || rest[at] != opener {
		return "", at, false
	}
	depth := 0
	for k := at; k < len(rest); k++ {
		switch rest[k] {
		case '\\':
			k++
		case opener:
			depth++
		case closer:
			depth--
			if depth == 0 {
				return rest[at+1 : k], k + 1, true
			}
		}
	}
	return "", len(rest), false
}

func skipSpaces(s string, at int) int {
	for at < len(s) && (s[at] == ' ' || s[at] == '\t') {
		at++
	}
	return at
}

func isLetter(c byte) bool { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '@' }

// Unmask puts back what every placeholder in s stands for. A placeholder
// this Masked never issued is left as it is (Verify refuses such text).
func (m Masked) Unmask(s string) string {
	var b strings.Builder
	for {
		i := strings.Index(s, phOpen)
		if i < 0 {
			b.WriteString(s)
			return b.String()
		}
		b.WriteString(s[:i])
		rest := s[i+len(phOpen):]
		j := strings.Index(rest, phClose)
		n, err := strconv.Atoi(rest[:max(j, 0)])
		if j < 0 || err != nil || n < 1 || n > len(m.spans) {
			b.WriteString(phOpen)
			s = rest
			continue
		}
		b.WriteString(m.spans[n-1])
		s = rest[j+len(phClose):]
	}
}

// placeholders lists the placeholder numbers in s in order, and whether s
// holds any bracket that is not part of a well-formed placeholder.
func placeholders(s string) ([]int, bool) {
	var found []int
	for {
		i := strings.Index(s, phOpen)
		c := strings.Index(s, phClose)
		if i < 0 {
			return found, c < 0
		}
		if c >= 0 && c < i {
			return found, false
		}
		rest := s[i+len(phOpen):]
		j := strings.Index(rest, phClose)
		if j < 0 {
			return found, false
		}
		n, err := strconv.Atoi(rest[:j])
		if err != nil || n < 1 {
			return found, false
		}
		found = append(found, n)
		s = rest[j+len(phClose):]
	}
}

// prose is how much of masked text is words a model could improve.
func prose(masked string) int {
	letters := 0
	inPlaceholder := false
	for _, r := range masked {
		switch {
		case r == '⟦':
			inPlaceholder = true
		case r == '⟧':
			inPlaceholder = false
		case !inPlaceholder && (r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r > 0x7f):
			letters++
		}
	}
	return letters
}
