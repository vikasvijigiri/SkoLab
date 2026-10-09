package main

import (
	"net/http"
	"net/http/httptest"
	"strconv"
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
		{`{"latex_source":"x","files":[{"path":"../x.tex","content_base64":""}]}`, "test-secret", 400},
		{`{"latex_source":"x","files":[{"path":"/etc/x","content_base64":""}]}`, "test-secret", 400},
		{`{"latex_source":"x","files":[{"path":"main.tex","content_base64":""}]}`, "test-secret", 400},
		{`{"latex_source":"x","files":[{"path":"a.tex","content_base64":"not base64"}]}`, "test-secret", 400},
		{`{"latex_source":"x","files":[{"path":"a.tex"},{"path":"A.tex"}]}`, "test-secret", 400},
		{`{"latex_source":"x","files":[` + strings.Repeat(`{"path":"a.tex"},`, 200) + `{"path":"b.tex"}]}`, "test-secret", 400},
		{`{"latex_source":"x","files":[{"path":"a.bin","content_base64":"` + strings.Repeat("A", (texsandbox.MaxFilesBytes/3+1)*4) + `"}]}`, "test-secret", 400},
	} {
		req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(tc.body))
		req.Header.Set("X-Internal-Token", tc.token)
		w := httptest.NewRecorder()
		handler(w, req)
		if w.Code != tc.status {
			t.Fatalf("%.80s: got %d, want %d", tc.body, w.Code, tc.status)
		}
	}
}

func TestWorkerAcceptsUnicodeSourceWithinGatewayCharacterLimit(t *testing.T) {
	handler := compileHandler("must-not-be-executed", texsandbox.NewSlots(1), "test-secret")
	// The forbidden primitive ensures no process runs while exercising the
	// UTF-8 request-size contract: this valid-sized source exceeds 200 KiB.
	body := `{"latex_source":"` + strings.Repeat("文", 99_900) + `\\openin1"}`
	req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(body))
	req.Header.Set("X-Internal-Token", "test-secret")
	w := httptest.NewRecorder()
	handler(w, req)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "Unsupported construct") {
		t.Fatalf("Unicode contract: %d %s", w.Code, w.Body)
	}
}

func TestWorkerAcceptsAFullSizeProject(t *testing.T) {
	handler := compileHandler("must-not-be-executed", texsandbox.NewSlots(1), "test-secret")
	// The largest valid request: maximal source and files, refused only by
	// the source policy, so no process runs.
	var files []string
	per := texsandbox.MaxFilesBytes / texsandbox.MaxFiles
	content := strings.Repeat("A", (per/3)*4)
	for i := 0; i < texsandbox.MaxFiles; i++ {
		files = append(files, `{"path":"`+strings.Repeat("d/", 5)+strings.Repeat("f", 70)+strconv.Itoa(i)+`.dat","content_base64":"`+content+`"}`)
	}
	body := `{"latex_source":"` + strings.Repeat("\\u6587", 99_990) + `\\openin1","files":[` + strings.Join(files, ",") + `]}`
	if len(body) < 14_000_000 {
		t.Fatalf("test body is only %d bytes", len(body))
	}
	req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(body))
	req.Header.Set("X-Internal-Token", "test-secret")
	w := httptest.NewRecorder()
	handler(w, req)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "Unsupported construct") {
		t.Fatalf("full-size project: %d %.300s", w.Code, w.Body)
	}
}
