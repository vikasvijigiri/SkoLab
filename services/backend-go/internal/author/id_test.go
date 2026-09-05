package author

import "testing"

func TestCleanAuthorID(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"A5086198262", "A5086198262"},
		{"https://openalex.org/A5086198262", "A5086198262"},
		{"0000-0002-1825-0097", "orcid:0000-0002-1825-0097"},
		{"0000-0002-1825-009X", "orcid:0000-0002-1825-009x"},
		{"https://orcid.org/0000-0003-1613-5981", "orcid:0000-0003-1613-5981"},
		{"orcid:0000-0003-1613-5981", "orcid:0000-0003-1613-5981"},
		{"  0000-0002-1825-0097  ", "orcid:0000-0002-1825-0097"},
	}
	for _, c := range cases {
		if got := cleanAuthorID(c.in); got != c.want {
			t.Errorf("cleanAuthorID(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
