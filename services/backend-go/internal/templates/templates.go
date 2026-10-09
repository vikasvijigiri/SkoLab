// Package templates is the editor's catalog of journal templates, built
// into the gateway binary so the catalog, the API and the compiler image
// always ship together:
//
//	GET /api/v1/templates               the catalog (no sources), ?domain= filters
//	GET /api/v1/templates/:id           one template with its LaTeX source
//
// Every template is the publisher's or class author's own, taken from the
// TeX Live release the compiler runs (so class and template versions match)
// and changed only where SkoLab's single-file compiler requires it; each
// file's header lists those changes. CI compiles every one of them through
// the production image (services/qa/templates_compile.py).
package templates

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"fmt"
	"net/http"
	"slices"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
)

//go:embed files/*.tex
var files embed.FS

// Domains lists the subject areas in display order.
var Domains = []string{"physics", "chemistry", "mathematics", "biology"}

type Template struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Domain      string `json:"domain"`
	Journals    string `json:"journals"`
	Publisher   string `json:"publisher"`
	Description string `json:"description"`
	Class       string `json:"class"`
	License     string `json:"license"`
	SourceURL   string `json:"source_url"`
	Size        int    `json:"size"` // bytes of source
	Source      string `json:"source,omitempty"`
	file        string
}

var (
	all    []Template
	byID   = map[string]Template{}
	etag   string
	listed []Template // all, without sources
)

func init() {
	if err := load(catalog); err != nil {
		panic(err)
	}
}

func load(entries []Template) error {
	sum := sha256.New()
	all, listed, byID = nil, nil, map[string]Template{}
	for _, t := range entries {
		raw, err := files.ReadFile("files/" + t.file)
		if err != nil {
			return fmt.Errorf("template %s: %w", t.ID, err)
		}
		if _, dup := byID[t.ID]; dup {
			return fmt.Errorf("template %s listed twice", t.ID)
		}
		if !slices.Contains(Domains, t.Domain) {
			return fmt.Errorf("template %s: unknown domain %q", t.ID, t.Domain)
		}
		t.Source, t.Size = string(raw), len(raw)
		sum.Write([]byte(t.ID))
		sum.Write(raw)
		all = append(all, t)
		byID[t.ID] = t
		summary := t
		summary.Source = ""
		listed = append(listed, summary)
	}
	etag = `"` + hex.EncodeToString(sum.Sum(nil))[:32] + `"`
	return nil
}

// Known reports whether id names a template in the catalog.
func Known(id string) bool {
	_, ok := byID[id]
	return ok
}

// All returns every template with its source, in catalog order.
func All() []Template { return slices.Clone(all) }

// Register mounts the routes on a group already protected by auth.VerifyUser().
func Register(group *gin.RouterGroup) {
	group.GET("/templates", list)
	group.GET("/templates/:id", get)
}

// fresh answers 304 when the client already holds this catalog. Unlike
// the rest of /api (no-store), templates are public text, so a browser may
// keep them but must revalidate: a deploy can change them.
func fresh(c *gin.Context) bool {
	c.Header("ETag", etag)
	c.Header("Cache-Control", "private, no-cache")
	if c.GetHeader("If-None-Match") == etag {
		c.Status(http.StatusNotModified)
		return true
	}
	return false
}

func list(c *gin.Context) {
	for name, values := range c.Request.URL.Query() {
		if name != "domain" {
			apierror.Abort(c, http.StatusBadRequest, "unknown_parameter", "Unknown query parameter: "+name)
			return
		}
		if len(values) > 1 {
			apierror.Abort(c, http.StatusBadRequest, "duplicate_parameter", "Query parameter given more than once: "+name)
			return
		}
	}
	domain, filtered := c.GetQuery("domain")
	if filtered && !slices.Contains(Domains, domain) {
		apierror.Abort(c, http.StatusBadRequest, "invalid_domain", "domain must be one of physics, chemistry, mathematics, biology")
		return
	}
	if fresh(c) {
		return
	}
	items := make([]Template, 0, len(listed))
	for _, t := range listed {
		if !filtered || t.Domain == domain {
			items = append(items, t)
		}
	}
	c.JSON(http.StatusOK, gin.H{"domains": Domains, "templates": items})
}

func get(c *gin.Context) {
	t, ok := byID[c.Param("id")]
	if !ok {
		apierror.Abort(c, http.StatusNotFound, "not_found", "No template with this id")
		return
	}
	if fresh(c) {
		return
	}
	c.JSON(http.StatusOK, t)
}
