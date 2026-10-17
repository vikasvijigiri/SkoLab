// Package templates is the editor's catalog of journal templates, built
// into the gateway binary so the catalog, the API and the compiler image
// always ship together:
//
//	GET /api/v1/templates               the catalog (no sources), ?domain= filters
//	GET /api/v1/templates/:id           one template with its LaTeX source
//	GET /api/v1/templates/:id/preview.jpg  a page of it as compiled, for the gallery
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
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
)

//go:embed files/*.tex
var files embed.FS

// Previews are a page of each template as the production compiler renders
// it: the first page, or the fullest of the first three when the first is a
// near-empty skeleton (services/qa/template_previews.py makes them from the
// PDFs CI compiles). Every template must have one.
//
//go:embed previews/*.jpg
var previews embed.FS

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
	// Preview is the path of its preview image, versioned by content so a
	// browser may keep it for good.
	Preview string `json:"preview"`
	Source  string `json:"source,omitempty"`
	file    string
}

var (
	all    []Template
	byID   = map[string]Template{}
	etag   string
	listed []Template // all, without sources
	images = map[string]image{}
)

type image struct {
	data []byte
	tag  string
}

func init() {
	if err := load(catalog); err != nil {
		panic(err)
	}
}

func load(entries []Template) error {
	sum := sha256.New()
	all, listed, byID, images = nil, nil, map[string]Template{}, map[string]image{}
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
		picture, err := previews.ReadFile("previews/" + t.ID + ".jpg")
		if err != nil {
			return fmt.Errorf("template %s has no preview: %w", t.ID, err)
		}
		digest := sha256.Sum256(picture)
		version := hex.EncodeToString(digest[:])[:16]
		images[t.ID] = image{data: picture, tag: `"` + version + `"`}
		t.Preview = "/api/v1/templates/" + t.ID + "/preview.jpg?v=" + version
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
	group.GET("/templates/:id/preview.jpg", preview)
}

// preview answers a template's preview image. The path carries the image's
// version (?v=), so a browser may keep it for a year; any other parameter is
// refused, as everywhere in the API.
// isVersion reports whether v has the shape of a preview version (16
// lower-case hex digits). Any such version gets the current picture, so an
// old link still shows the template.
func isVersion(v string) bool {
	if len(v) != 16 {
		return false
	}
	for _, r := range v {
		if (r < '0' || r > '9') && (r < 'a' || r > 'f') {
			return false
		}
	}
	return true
}

func preview(c *gin.Context) {
	for name, values := range c.Request.URL.Query() {
		if name != "v" {
			apierror.Abort(c, http.StatusBadRequest, "unknown_parameter", "Unknown query parameter: "+name)
			return
		}
		if len(values) > 1 {
			apierror.Abort(c, http.StatusBadRequest, "duplicate_parameter", "Query parameter given more than once: "+name)
			return
		}
		if !isVersion(values[0]) {
			apierror.Abort(c, http.StatusBadRequest, "invalid_version", "v is the 16-hex version from the preview URL")
			return
		}
	}
	picture, ok := images[c.Param("id")]
	if !ok {
		apierror.Abort(c, http.StatusNotFound, "not_found", "No template with this id")
		return
	}
	c.Header("ETag", picture.tag)
	c.Header("Cache-Control", "private, max-age=31536000, immutable")
	if matches(c.GetHeader("If-None-Match"), picture.tag) {
		c.Status(http.StatusNotModified)
		return
	}
	c.Data(http.StatusOK, "image/jpeg", picture.data)
}

// fresh answers 304 when the client already holds this catalog. Unlike
// the rest of /api (no-store), templates are public text, so a browser may
// keep them but must revalidate: a deploy can change them.
func fresh(c *gin.Context) bool {
	c.Header("ETag", etag)
	c.Header("Cache-Control", "private, no-cache")
	if matches(c.GetHeader("If-None-Match"), etag) {
		c.Status(http.StatusNotModified)
		return true
	}
	return false
}

// matches compares If-None-Match weakly over a comma-separated list, as
// RFC 9110 asks: Render's edge compresses responses and so marks the ETag
// weak (W/"..."), and a browser sends that back. "*" is not honoured: no
// client of ours sends it, and a 304 for it would hide the catalog.
func matches(header, tag string) bool {
	for _, candidate := range strings.Split(header, ",") {
		candidate = strings.TrimPrefix(strings.TrimSpace(candidate), "W/")
		if candidate == tag {
			return true
		}
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
