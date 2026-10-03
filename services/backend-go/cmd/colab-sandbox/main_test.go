package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/skolab/backend-go/internal/texsandbox"
)

func TestCompileWorkerRefusesUnauthorizedAndInvalidInput(t *testing.T) {
	handler := compileHandler("must-not-be-executed", texsandbox.NewSlots(1), "test-secret")
	for _, tc := range []struct {
		body, token string
		status      int
	}{
		{`{"latex_source":"hello"}`, "", 401},
		{`{"latex_source":"hello","engine":"xelatex"}`, "test-secret", 400},
		{`{"latex_source":"` + strings.Repeat("x", 100_001) + `"}`, "test-secret", 400},
		{strings.Repeat("x", maxRequestBytes+1), "test-secret", 413},
	} {
		req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(tc.body))
		req.Header.Set("X-Internal-Token", tc.token)
		w := httptest.NewRecorder()
		handler(w, req)
		if w.Code != tc.status {
			t.Fatalf("got %d, want %d", w.Code, tc.status)
		}
	}
}

func TestWorkerAcceptsUnicodeSourceWithinGatewayCharacterLimit(t *testing.T) {
	handler := compileHandler("must-not-be-executed", texsandbox.NewSlots(1), "test-secret")
	// The forbidden primitive ensures no process runs while exercising the
	// UTF-8 request-size contract: this valid-sized source exceeds 200 KiB.
	body := `{"latex_source":"` + strings.Repeat("文", 99_900) + `\\input{/etc/passwd}"}`
	req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(body))
	req.Header.Set("X-Internal-Token", "test-secret")
	w := httptest.NewRecorder()
	handler(w, req)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "Unsupported construct") {
		t.Fatalf("Unicode contract: %d %s", w.Code, w.Body)
	}
}
