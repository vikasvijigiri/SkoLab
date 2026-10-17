package ai

import (
	"slices"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
)

// Edit is what the model proposed, in masked text.
type Edit struct {
	Original    string `json:"original"`
	Replacement string `json:"replacement"`
	Reason      string `json:"reason"`
	Category    string `json:"category"`
}

// Suggestion is an edit that passed every check, in the reader's own text.
// Start and End are UTF-16 code unit offsets into the text that was sent
// (what a browser string indexes), so the editor can apply it exactly.
type Suggestion struct {
	ID          string `json:"id"`
	Original    string `json:"original"`
	Replacement string `json:"replacement"`
	Reason      string `json:"reason"`
	Category    string `json:"category"`
	Start       int    `json:"start"`
	End         int    `json:"end"`
}

// Why an edit was refused, for the edits metric.
const (
	refusedNotFound     = "not_found"
	refusedOverlap      = "overlap"
	refusedPlaceholders = "placeholders"
	refusedBraces       = "braces"
	refusedSize         = "size"
	refusedUnchanged    = "unchanged"
)

var categories = map[string]bool{"clarity": true, "concision": true, "tone": true, "grammar": true, "flow": true, "word_choice": true}

// Verify keeps the edits that are safe to show for text masked as m:
// each quotes the masked text exactly and does not overlap an earlier one,
// keeps exactly the same placeholders in the same order and adds no stray
// bracket, keeps braces balanced the same way, and is of sensible size.
// It answers the suggestions in text order and why the others were refused.
func Verify(m Masked, edits []Edit) ([]Suggestion, []string) {
	type span struct{ from, to int }
	var taken []span
	var accepted []Suggestion
	var refused []string
	overlaps := func(from, to int) bool {
		for _, s := range taken {
			if from < s.to && s.from < to {
				return true
			}
		}
		return false
	}
	for _, e := range edits {
		if e.Original == "" || e.Original == e.Replacement {
			refused = append(refused, refusedUnchanged)
			continue
		}
		if len(e.Replacement) > 3*len(e.Original)+200 {
			refused = append(refused, refusedSize)
			continue
		}
		before, okBefore := placeholders(e.Original)
		after, okAfter := placeholders(e.Replacement)
		if !okBefore || !okAfter || !slices.Equal(before, after) {
			refused = append(refused, refusedPlaceholders)
			continue
		}
		if braces(e.Original) != braces(e.Replacement) {
			refused = append(refused, refusedBraces)
			continue
		}
		at, found, clash := -1, false, false
		for from := 0; ; {
			k := strings.Index(m.Text[from:], e.Original)
			if k < 0 {
				break
			}
			found = true
			if !overlaps(from+k, from+k+len(e.Original)) {
				at = from + k
				break
			}
			clash = true
			from += k + 1
		}
		if at < 0 {
			if found && clash {
				refused = append(refused, refusedOverlap)
			} else {
				refused = append(refused, refusedNotFound)
			}
			continue
		}
		taken = append(taken, span{at, at + len(e.Original)})
		category := e.Category
		if !categories[category] {
			category = "clarity"
		}
		original := m.Unmask(e.Original)
		start := units(m.Unmask(m.Text[:at]))
		accepted = append(accepted, Suggestion{
			Original:    original,
			Replacement: m.Unmask(e.Replacement),
			Reason:      strings.TrimSpace(e.Reason),
			Category:    category,
			Start:       start,
			End:         start + units(original),
		})
	}
	sort.Slice(accepted, func(i, j int) bool { return accepted[i].Start < accepted[j].Start })
	for i := range accepted {
		accepted[i].ID = "s" + strconv.Itoa(i+1)
	}
	return accepted, refused
}

// braces is the net { minus } in s, ignoring escaped braces (\{ and \}).
func braces(s string) int {
	n := 0
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '\\':
			i++
		case '{':
			n++
		case '}':
			n--
		}
	}
	return n
}

// units is the length of s in UTF-16 code units.
func units(s string) int {
	n := 0
	for _, r := range s {
		n += utf16.RuneLen(r)
	}
	return n
}
