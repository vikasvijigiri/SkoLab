// Package vitals receives Core Web Vitals from the web app's own visitors
// (real-user monitoring) and hands them to the metrics pipeline, so page
// speed is watched in Grafana next to the API's own latency.
//
// The endpoint is public: the sign-in pages are measured before anyone signs
// in. It accepts only a short, fixed shape: known metric names, finite values
// in a sane range, and a page from a short allowlist, so a stranger can't
// grow the metric's label set or store anything. Requests carry no user or
// session identifier.
package vitals

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"mime"
	"net/http"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
)

// maxPage bounds the page field in characters; the app's routes are far shorter.
const maxPage = 100

// maxSamples bounds one report: each metric at most once, plus room for a
// page that reports INP and CLS again as they grow.
const maxSamples = 10

// Sample is one measurement, already validated.
type Sample struct {
	Name  string  // LCP, INP, CLS, FCP or TTFB
	Page  string  // a page from the allowlist, or "other"
	Value float64 // seconds for timings; unitless for CLS
}

// Recorder stores samples; the telemetry package implements it.
type Recorder interface {
	RecordVital(ctx context.Context, s Sample)
}

// limits holds each metric's largest believable value in the browser's own
// unit (milliseconds for timings, unitless for CLS) and how to convert it.
var limits = map[string]struct {
	max   float64
	scale float64
}{
	"LCP":  {max: 60_000, scale: 1e-3},
	"INP":  {max: 60_000, scale: 1e-3},
	"FCP":  {max: 60_000, scale: 1e-3},
	"TTFB": {max: 60_000, scale: 1e-3},
	"CLS":  {max: 100, scale: 1},
}

// pages are the web app's routes, by first path segment. Anything else is
// reported as "other" rather than rejected, so a new page still counts.
var pages = map[string]bool{
	"/": true, "/sign-in": true, "/sign-up": true, "/forgot-password": true,
	"/verify-email": true, "/editor": true, "/invite": true, "/projects": true,
	"/templates": true,
}

type report struct {
	Page    *string `json:"page"` // required: a missing or null page is refused
	Metrics []*struct {
		Name  string   `json:"name"`
		Value *float64 `json:"value"`
	} `json:"metrics"`
}

var errInvalid = errors.New("invalid report")

// parse validates a report and returns its samples. Fields outside the
// contract are refused, so a report can't smuggle in anything else.
func parse(body []byte) ([]Sample, error) {
	var r report
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&r); err != nil || decoder.More() {
		return nil, errInvalid
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, errInvalid // trailing data after the report
	}
	if len(r.Metrics) == 0 || len(r.Metrics) > maxSamples {
		return nil, errInvalid
	}
	if r.Page == nil || utf8.RuneCountInString(*r.Page) > maxPage { // characters, as JSON Schema counts
		return nil, errInvalid
	}
	page := *r.Page
	if !pages[page] {
		page = "other"
	}
	samples := make([]Sample, 0, len(r.Metrics))
	for _, m := range r.Metrics {
		if m == nil {
			return nil, errInvalid
		}
		limit, known := limits[m.Name]
		if !known || m.Value == nil {
			return nil, errInvalid
		}
		v := *m.Value
		if math.IsNaN(v) || math.IsInf(v, 0) || v < 0 || v > limit.max {
			return nil, errInvalid
		}
		samples = append(samples, Sample{Name: m.Name, Page: page, Value: v * limit.scale})
	}
	return samples, nil
}

// Handler answers POST /api/v1/vitals. Browsers send it with
// navigator.sendBeacon as text/plain (a CORS "simple" request, so no
// preflight); application/json is accepted too.
func Handler(rec Recorder) gin.HandlerFunc {
	return func(c *gin.Context) {
		if media, _, err := mime.ParseMediaType(c.GetHeader("Content-Type")); err != nil || (media != "text/plain" && media != "application/json") {
			apierror.Abort(c, http.StatusUnsupportedMediaType, "unsupported_media_type", "Send the report as application/json or text/plain")
			return
		}
		// The gateway-wide body limit (middleware.BodyLimit) answers 413.
		body, err := io.ReadAll(c.Request.Body)
		if err != nil {
			apierror.Abort(c, http.StatusBadRequest, "invalid_body", "Request body could not be read")
			return
		}
		samples, err := parse(body)
		if err != nil {
			apierror.Abort(c, http.StatusBadRequest, "invalid_vitals", "Expected {page, metrics: [{name, value}]} with known metric names and values in range")
			return
		}
		for _, s := range samples {
			rec.RecordVital(c.Request.Context(), s)
		}
		c.Status(http.StatusNoContent)
	}
}
