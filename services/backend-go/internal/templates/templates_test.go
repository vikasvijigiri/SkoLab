package templates

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func serve(t *testing.T, method, path string, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	Register(r.Group("/api/v1"))
	req := httptest.NewRequest(method, path, nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

type listBody struct {
	Domains   []string   `json:"domains"`
	Templates []Template `json:"templates"`
}

func TestEveryDomainHasTwoOrThreeTemplates(t *testing.T) {
	count := map[string]int{}
	for _, tpl := range All() {
		count[tpl.Domain]++
	}
	for _, domain := range Domains {
		if count[domain] < 2 || count[domain] > 3 {
			t.Errorf("%s has %d templates, want 2 or 3", domain, count[domain])
		}
	}
}

// The compiler takes one file, runs no BibTeX and refuses file access
// (services/backend/app/api/v1/endpoints/colab.py _FORBIDDEN_PRIMITIVES).
var forbidden = regexp.MustCompile(`\\(input|include|openin|openout|read|readline|write|newread|newwrite|verbatiminput|lstinputlisting)([^a-zA-Z]|$)`)

func TestEveryTemplateIsASelfContainedDocumentWithItsProvenance(t *testing.T) {
	for _, tpl := range All() {
		src := tpl.Source
		if !strings.HasPrefix(src, "%% SkoLab copy of ") {
			t.Errorf("%s: the header must say where the file comes from and what changed", tpl.ID)
		}
		if !strings.Contains(src, `\documentclass`) || !strings.Contains(src, `\begin{document}`) || !strings.Contains(src, `\end{document}`) {
			t.Errorf("%s: not a complete LaTeX document", tpl.ID)
		}
		if !strings.Contains(src, "{"+tpl.Class+"}") {
			t.Errorf("%s: does not use the %s class it is listed with", tpl.ID, tpl.Class)
		}
		verbatim := false // examples shown in a verbatim block are text, not code
		for _, line := range strings.Split(src, "\n") {
			code, _, _ := strings.Cut(line, "%")
			switch {
			case strings.Contains(code, `\begin{verbatim}`):
				verbatim = true
			case strings.Contains(code, `\end{verbatim}`):
				verbatim = false
			}
			if verbatim {
				continue
			}
			if m := forbidden.FindString(code); m != "" {
				t.Errorf("%s: uses %s, which the compiler refuses", tpl.ID, m)
			}
			if strings.Contains(code, `\bibliography{`) {
				t.Errorf("%s: needs a .bib file the compiler cannot read: %s", tpl.ID, line)
			}
		}
		if len(src) > 100_000 {
			t.Errorf("%s: %d bytes is over the compiler's 100,000 character limit", tpl.ID, len(src))
		}
		if tpl.Name == "" || tpl.Journals == "" || tpl.Publisher == "" || tpl.Description == "" || tpl.License == "" ||
			!strings.HasPrefix(tpl.SourceURL, "https://ctan.org/pkg/") {
			t.Errorf("%s: incomplete catalog entry", tpl.ID)
		}
		if len(tpl.ID) > 64 || !regexp.MustCompile(`^[a-z0-9-]+$`).MatchString(tpl.ID) {
			t.Errorf("%s: ids are lowercase slugs of at most 64 characters", tpl.ID)
		}
	}
}

func TestListOmitsSourcesAndKeepsCatalogOrder(t *testing.T) {
	w := serve(t, http.MethodGet, "/api/v1/templates", nil)
	var body listBody
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &body) != nil {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	if len(body.Templates) != len(catalog) || strings.Join(body.Domains, ",") != "physics,chemistry,mathematics,biology" {
		t.Fatalf("%+v", body)
	}
	for i, tpl := range body.Templates {
		if tpl.ID != catalog[i].ID || tpl.Source != "" || tpl.Size == 0 {
			t.Fatalf("item %d = %+v", i, tpl)
		}
	}
	if w.Header().Get("ETag") == "" || w.Header().Get("Cache-Control") != "private, no-cache" {
		t.Fatalf("headers %v", w.Header())
	}
}

func TestListFiltersByDomain(t *testing.T) {
	w := serve(t, http.MethodGet, "/api/v1/templates?domain=chemistry", nil)
	var body listBody
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	if w.Code != http.StatusOK || len(body.Templates) == 0 {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	for _, tpl := range body.Templates {
		if tpl.Domain != "chemistry" {
			t.Fatalf("%s is %s", tpl.ID, tpl.Domain)
		}
	}
}

func TestListRefusesUnknownOrRepeatedParameters(t *testing.T) {
	for path, code := range map[string]string{
		"/api/v1/templates?domain=astrology":                "invalid_domain",
		"/api/v1/templates?domain=":                         "invalid_domain",
		"/api/v1/templates?domian=physics":                  "unknown_parameter",
		"/api/v1/templates?domain=physics&domain=chemistry": "duplicate_parameter",
	} {
		w := serve(t, http.MethodGet, path, nil)
		var body struct{ Code string }
		_ = json.Unmarshal(w.Body.Bytes(), &body)
		if w.Code != http.StatusBadRequest || body.Code != code {
			t.Errorf("%s: %d %s", path, w.Code, w.Body)
		}
	}
}

func TestGetReturnsTheSource(t *testing.T) {
	w := serve(t, http.MethodGet, "/api/v1/templates/acs-jacs", nil)
	var tpl Template
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &tpl) != nil {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	if tpl.ID != "acs-jacs" || !strings.Contains(tpl.Source, `{achemso}`) || tpl.Size != len(tpl.Source) {
		t.Fatalf("%+v", tpl.ID)
	}
}

func TestUnknownTemplateIs404(t *testing.T) {
	if w := serve(t, http.MethodGet, "/api/v1/templates/nope", nil); w.Code != http.StatusNotFound {
		t.Fatalf("%d", w.Code)
	}
	if Known("nope") || !Known("nature") {
		t.Fatal("Known disagrees with the catalog")
	}
}

func TestMatchingETagIs304WithoutABody(t *testing.T) {
	first := serve(t, http.MethodGet, "/api/v1/templates/nature", nil)
	again := serve(t, http.MethodGet, "/api/v1/templates/nature", map[string]string{"If-None-Match": first.Header().Get("ETag")})
	if again.Code != http.StatusNotModified || again.Body.Len() != 0 {
		t.Fatalf("%d %q", again.Code, again.Body)
	}
	stale := serve(t, http.MethodGet, "/api/v1/templates", map[string]string{"If-None-Match": `"old"`})
	if stale.Code != http.StatusOK {
		t.Fatalf("stale etag: %d", stale.Code)
	}
}

func TestLoadRefusesABrokenCatalog(t *testing.T) {
	defer func() { _ = load(catalog) }()
	if load([]Template{{ID: "x", file: "missing.tex", Domain: "physics"}}) == nil {
		t.Error("missing file accepted")
	}
	if load([]Template{{ID: "x", file: "nature.tex", Domain: "astrology"}}) == nil {
		t.Error("unknown domain accepted")
	}
	if load([]Template{{ID: "x", file: "nature.tex", Domain: "biology"}, {ID: "x", file: "nature.tex", Domain: "biology"}}) == nil {
		t.Error("duplicate id accepted")
	}
}

// The OpenAPI contract lists the template ids so contract fuzzing sends
// real ones; it must match the catalog.
func TestTheContractListsExactlyTheCatalogIDs(t *testing.T) {
	spec, err := os.ReadFile("../../api/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	m := regexp.MustCompile(`(?m)^    TemplateId:\n(?:      .*\n)*?      enum: \[([^\]]*)\]`).FindSubmatch(spec)
	if m == nil {
		t.Fatal("components.schemas.TemplateId.enum not found in api/openapi.yaml")
	}
	var want []string
	for _, tpl := range catalog {
		want = append(want, tpl.ID)
	}
	if got := strings.Split(strings.ReplaceAll(string(m[1]), " ", ""), ","); strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("contract lists %v, catalog has %v", got, want)
	}
}
