package cache

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/skolab/backend-go/internal/db"
)

// These tests never touch a database. db.Pool is nil in the test binary, which
// is exactly the degraded path Python swallows — so they assert the no-DB
// contract plus the pure key/envelope helpers.

func TestPgKeyPrefixing(t *testing.T) {
	cases := []struct{ name, key, want string }{
		{"pipeline", "citation_heatmap_A123", "pipeline::citation_heatmap_A123"},
		{"profile", "", "profile::"},
		{"a", "b::c", "a::b::c"},
	}
	for _, tc := range cases {
		if got := pgKey(tc.name, tc.key); got != tc.want {
			t.Errorf("pgKey(%q,%q) = %q, want %q", tc.name, tc.key, got, tc.want)
		}
	}
}

func TestEnvelopeRoundTrip(t *testing.T) {
	cases := []struct {
		name    string
		value   any
		wantOK  bool
		wantRaw string
	}{
		{"scalar int", 42, true, "42"},
		{"string", "hi", true, `"hi"`},
		{"map", map[string]any{"years": []int{2021, 2022}}, true, `{"years":[2021,2022]}`},
		{"slice", []int{1, 2, 3}, true, "[1,2,3]"},
		{"empty slice", []int{}, true, "[]"},
		{"nil", nil, false, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b, err := wrapEnvelope(tc.value)
			if err != nil {
				t.Fatalf("wrapEnvelope: %v", err)
			}
			// The envelope must always be a {"v": ...} object.
			var probe map[string]json.RawMessage
			if err := json.Unmarshal(b, &probe); err != nil {
				t.Fatalf("envelope is not a JSON object: %v (%s)", err, b)
			}
			if _, hasV := probe["v"]; !hasV {
				t.Fatalf("envelope missing \"v\" key: %s", b)
			}

			raw, ok := unwrapEnvelope(b)
			if ok != tc.wantOK {
				t.Fatalf("unwrapEnvelope ok = %v, want %v (env=%s)", ok, tc.wantOK, b)
			}
			if !tc.wantOK {
				return
			}
			if string(raw) != tc.wantRaw {
				t.Errorf("inner raw JSON = %s, want %s", raw, tc.wantRaw)
			}
			// Inner JSON must be independently valid.
			if !json.Valid(raw) {
				t.Errorf("inner raw JSON is invalid: %s", raw)
			}
		})
	}
}

func TestUnwrapEnvelopeRejectsGarbage(t *testing.T) {
	for _, in := range []string{``, `not json`, `{}`, `{"x":1}`, `{"v":null}`, `[]`} {
		if raw, ok := unwrapEnvelope([]byte(in)); ok {
			t.Errorf("unwrapEnvelope(%q) = (%s, true), want ok=false", in, raw)
		}
	}
}

func TestPgFuncsAreNoOpWithoutPool(t *testing.T) {
	if db.Pool != nil {
		t.Skip("db.Pool is set — this test only covers the no-DB degraded path")
	}
	ctx := context.Background()

	if got, ok := PgGet(ctx, "pipeline", "missing"); ok || got != nil {
		t.Errorf("PgGet with nil pool = (%s, %v), want (nil, false)", got, ok)
	}
	if err := PgSet(ctx, "pipeline", "k", map[string]int{"a": 1}, time.Hour); err != nil {
		t.Errorf("PgSet with nil pool = %v, want nil", err)
	}
	if err := PgDelete(ctx, "pipeline", "k"); err != nil {
		t.Errorf("PgDelete with nil pool = %v, want nil", err)
	}
	if err := PgClear(ctx, "pipeline"); err != nil {
		t.Errorf("PgClear with nil pool = %v, want nil", err)
	}
}
