package author

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestGetCoachPulseWithNoParamsReturnsAllNilNoNetworkCall(t *testing.T) {
	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/v1/coach_pulse", nil)

	GetCoachPulse(c)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var resp CoachPulseResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if resp.Impact != nil || resp.TrackedActivity != nil || resp.WorthTracking != nil {
		t.Errorf("expected all-nil fields with no query params, got %+v", resp)
	}
}

func TestCleanOpenAlexID(t *testing.T) {
	cases := map[string]string{
		"https://openalex.org/A5023888391": "A5023888391",
		"A5023888391":                      "A5023888391",
		"  a5023888391  ":                  "A5023888391",
		"":                                 "",
	}
	for in, want := range cases {
		if got := cleanOpenAlexID(in); got != want {
			t.Errorf("cleanOpenAlexID(%q) = %q, want %q", in, got, want)
		}
	}
}
