package files

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/skolab/backend-go/internal/workspace"
)

// fakeStore records what handlers ask for and answers with err when set.
type fakeStore struct {
	err      error
	created  []NewFile
	uploaded []NewFile
	replace  bool
	saved    []string
	moved    string
	deleted  string
	bundle   Bundle
	output   []byte
	imported *imported
}

var at = time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC)

func fileOf(f NewFile) File {
	out := File{ID: "f-1", Path: f.Path, Kind: f.Kind, Size: f.size(), ContentType: f.ContentType, Version: 1, CreatedAt: at, UpdatedAt: at}
	if f.Kind == KindText {
		out.Content = &f.Text
	}
	return out
}

func (s *fakeStore) List(_ context.Context, ws, _ string) (Listing, error) {
	if s.err != nil {
		return Listing{}, s.err
	}
	return Listing{WorkspaceID: ws, Role: "editor", Main: Main{Path: MainPath, Version: 2}, Files: []File{fileOf(NewFile{Path: "refs.bib", Kind: KindText})},
		Usage: Usage{Bytes: 0, LimitBytes: MaxProjectBytes, Entries: 1, LimitEntries: MaxEntries}}, nil
}

func (s *fakeStore) Create(_ context.Context, _, _ string, f NewFile) (File, error) {
	s.created = append(s.created, f)
	if s.err != nil {
		return File{}, s.err
	}
	return fileOf(f), nil
}

func (s *fakeStore) Upload(_ context.Context, _, _ string, files []NewFile, replace bool) ([]File, error) {
	s.uploaded, s.replace = files, replace
	if s.err != nil {
		return nil, s.err
	}
	out := []File{}
	for _, f := range files {
		stored := fileOf(f)
		stored.Content = nil
		out = append(out, stored)
	}
	return out, nil
}

func (s *fakeStore) Get(_ context.Context, _, _, id string) (File, []byte, error) {
	if s.err != nil {
		return File{}, nil, s.err
	}
	switch id {
	case "folder":
		return fileOf(NewFile{Path: "fig", Kind: KindFolder}), nil, nil
	case "image":
		return fileOf(NewFile{Path: "fig/a.png", Kind: KindBinary, Data: pngBytes, ContentType: "image/png"}), pngBytes, nil
	case "pdf":
		return fileOf(NewFile{Path: "paper.pdf", Kind: KindBinary, Data: pdfBytes, ContentType: "application/pdf"}), pdfBytes, nil
	}
	f := fileOf(NewFile{Path: "chapters/intro.tex", Kind: KindText, Text: "Hello", ContentType: "text/x-tex"})
	return f, []byte("Hello"), nil
}

func (s *fakeStore) SaveText(_ context.Context, _, _, id, content string, base int) (File, error) {
	s.saved = append(s.saved, id+"|"+content)
	if s.err != nil {
		return File{}, s.err
	}
	f := fileOf(NewFile{Path: "intro.tex", Kind: KindText, Text: content})
	f.Version = base + 1
	return f, nil
}

func (s *fakeStore) Move(_ context.Context, _, _, _, p string) (File, error) {
	s.moved = p
	if s.err != nil {
		return File{}, s.err
	}
	return fileOf(NewFile{Path: p, Kind: KindFolder}), nil
}

func (s *fakeStore) Delete(_ context.Context, _, _, id string) error {
	s.deleted = id
	return s.err
}

func (s *fakeStore) Bundle(_ context.Context, _, _ string, withOutput bool) (Bundle, error) {
	b := s.bundle
	if !withOutput {
		b.Output = nil
	}
	return b, s.err
}

func (s *fakeStore) SaveOutput(_ context.Context, _, _ string, pdf []byte) (Output, error) {
	s.output = pdf
	return Output{Size: len(pdf)}, nil
}

func (s *fakeStore) Output(_ context.Context, _, _ string) (string, []byte, error) {
	if s.err != nil {
		return "", nil, s.err
	}
	return "My Paper", pdfBytes, nil
}

func (s *fakeStore) Import(_ context.Context, uid, title, main string, files []NewFile) (workspace.Workspace, error) {
	s.imported = &imported{Main: main, Files: files}
	if s.err != nil {
		return workspace.Workspace{}, s.err
	}
	return workspace.Workspace{ID: "ws-new", Title: title, OwnerID: uid, Role: "owner", CreatedAt: at}, nil
}

// fakeCompiler answers like internal/colab's Service.
type fakeCompiler struct {
	body   []byte
	answer string
	fail   bool
}

func (f *fakeCompiler) Run(c *gin.Context, _ string, body []byte) ([]byte, bool) {
	f.body = body
	if f.fail {
		c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"code": "compile_in_progress"})
		return nil, false
	}
	return []byte(f.answer), true
}

func serve(t *testing.T, store Store, compiler Compiler, user string, req *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	group := r.Group("/api/v1", func(c *gin.Context) {
		if user != "" {
			c.Set("user_id", user)
		}
	})
	Register(group, store, compiler)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func jsonReq(method, path, body string) *http.Request {
	return httptest.NewRequest(method, path, strings.NewReader(body))
}

func decoded(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatalf("not JSON: %s", w.Body)
	}
	return out
}

const base = "/api/v1/workspaces/ws-1"

func TestEveryRouteNeedsAVerifiedCaller(t *testing.T) {
	routes := [][2]string{
		{"GET", base + "/files"}, {"POST", base + "/files"}, {"POST", base + "/files/upload"},
		{"GET", base + "/files/x"}, {"GET", base + "/files/x/raw"}, {"PUT", base + "/files/x"},
		{"PATCH", base + "/files/x"}, {"DELETE", base + "/files/x"}, {"GET", base + "/archive"},
		{"POST", base + "/compile"}, {"GET", base + "/output.pdf"}, {"POST", "/api/v1/workspaces/import"},
	}
	for _, route := range routes {
		w := serve(t, &fakeStore{}, &fakeCompiler{}, "", jsonReq(route[0], route[1], "{}"))
		if w.Code != http.StatusUnauthorized || decoded(t, w)["code"] != "unauthenticated" || w.Header().Get("Cache-Control") != "no-store" {
			t.Errorf("%v: %d %s", route, w.Code, w.Body)
		}
	}
}

func TestListAnswersTheListing(t *testing.T) {
	w := serve(t, &fakeStore{}, nil, "alice", jsonReq("GET", base+"/files", ""))
	got := decoded(t, w)
	if w.Code != http.StatusOK || got["workspace_id"] != "ws-1" || got["output"] != nil {
		t.Fatalf("%d %v", w.Code, got)
	}
	if files := got["files"].([]any); len(files) != 1 || files[0].(map[string]any)["content"] != "" {
		t.Fatalf("%v", got["files"])
	}
}

func TestStoreErrorsMapToTheContract(t *testing.T) {
	cases := map[error][2]any{
		ErrNotFound:                  {http.StatusNotFound, "not_found"},
		ErrReadOnly:                  {http.StatusForbidden, "edit_forbidden"},
		ErrExists:                    {http.StatusConflict, "file_exists"},
		ErrFull:                      {http.StatusRequestEntityTooLarge, "project_full"},
		ErrNotText:                   {http.StatusBadRequest, "not_a_text_file"},
		ErrInvalidMove:               {http.StatusBadRequest, "invalid_path"},
		ErrTypeChange:                {http.StatusUnsupportedMediaType, "unsupported_file_type"},
		ErrNoOutput:                  {http.StatusNotFound, "no_output"},
		workspace.ErrLimitReached:    {http.StatusForbidden, "workspace_limit_reached"},
		workspace.ErrProfileRequired: {http.StatusConflict, "profile_required"},
		ErrUnavailable:               {http.StatusServiceUnavailable, "unavailable"},
		&ConflictError{Current: 7}:   {http.StatusConflict, "version_conflict"},
	}
	for err, want := range cases {
		w := serve(t, &fakeStore{err: err}, nil, "alice", jsonReq("PUT", base+"/files/x", `{"content":"a","base_version":1}`))
		if got := decoded(t, w); w.Code != want[0] || got["code"] != want[1] {
			t.Errorf("%v: %d %v", err, w.Code, got)
		}
	}
	w := serve(t, &fakeStore{err: &ConflictError{Current: 7}}, nil, "alice", jsonReq("PUT", base+"/files/x", `{"content":"a","base_version":1}`))
	if decoded(t, w)["current_version"] != float64(7) || w.Header().Get("ETag") != `"v7"` {
		t.Fatalf("%v %v", w.Body, w.Header())
	}
}

func TestCreateValidatesBeforeTheStore(t *testing.T) {
	store := &fakeStore{}
	w := serve(t, store, nil, "alice", jsonReq("POST", base+"/files", `{"path":"chapters/intro.tex","kind":"text","content":"Hi"}`))
	if w.Code != http.StatusCreated || decoded(t, w)["content"] != "Hi" || w.Header().Get("ETag") != `"v1"` {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	w = serve(t, store, nil, "alice", jsonReq("POST", base+"/files", `{"path":"figures","kind":"folder"}`))
	if w.Code != http.StatusCreated || store.created[1].Kind != KindFolder {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	bad := map[string]string{
		`{"path":"x.tex"}`:                                  "invalid_body",
		`{"path":"x.tex","kind":"binary"}`:                  "invalid_body",
		`{"path":"x","kind":"folder","content":""}`:         "invalid_body",
		`{"kind":"text"}`:                                   "invalid_body",
		`{"path":"x.tex","kind":"text","extra":1}`:          "invalid_body",
		`{"path":"x.tex","kind":"text"} {}`:                 "invalid_body",
		`not json`:                                          "invalid_body",
		`{"path":"../x.tex","kind":"text"}`:                 "invalid_path",
		`{"path":"main.tex","kind":"text"}`:                 "reserved_path",
		`{"path":"output.pdf","kind":"folder"}`:             "reserved_path",
		`{"path":"a//b","kind":"folder"}`:                   "invalid_path",
		`{"path":"x.png","kind":"text"}`:                    "unsupported_file_type",
		`{"path":"x.tex","kind":"text","content":"\u0000"}`: "invalid_text",
	}
	for body, code := range bad {
		w := serve(t, store, nil, "alice", jsonReq("POST", base+"/files", body))
		if got := decoded(t, w); got["code"] != code {
			t.Errorf("%s: %d %v", body, w.Code, got)
		}
	}
	if len(store.created) != 2 {
		t.Fatalf("invalid requests reached the store: %d", len(store.created))
	}
}

type part struct{ field, name, content string }

func multipartReq(t *testing.T, path string, parts ...part) *http.Request {
	t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	for _, p := range parts {
		var err error
		if p.name != "" {
			var fw interface{ Write([]byte) (int, error) }
			fw, err = w.CreateFormFile(p.field, p.name)
			if err == nil {
				_, err = fw.Write([]byte(p.content))
			}
		} else {
			err = w.WriteField(p.field, p.content)
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest("POST", path, &buf)
	req.Header.Set("Content-Type", w.FormDataContentType())
	return req
}

func TestUploadClassifiesEveryFileIntoTheFolder(t *testing.T) {
	store := &fakeStore{}
	w := serve(t, store, nil, "alice", multipartReq(t, base+"/files/upload",
		part{"folder", "", "/figures/"}, part{"replace", "", "true"},
		part{"file", "plot.png", string(pngBytes)}, part{"file", `C:\Users\me\refs.bib`, "@a{}"}))
	if w.Code != http.StatusCreated {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	if len(store.uploaded) != 2 || store.uploaded[0].Path != "figures/plot.png" || store.uploaded[1].Path != "figures/refs.bib" || !store.replace {
		t.Fatalf("%+v %v", store.uploaded, store.replace)
	}
	files := decoded(t, w)["files"].([]any)
	if len(files) != 2 || files[1].(map[string]any)["content"] != nil {
		t.Fatalf("an upload answer carries no content: %v", files)
	}
}

func TestUploadRefusals(t *testing.T) {
	cases := map[string]struct {
		parts []part
		code  string
	}{
		"no parts":        {nil, "invalid_body"},
		"unknown field":   {[]part{{"other", "", "x"}}, "invalid_body"},
		"nameless file":   {[]part{{"file", "", "x"}}, "invalid_body"},
		"bad folder":      {[]part{{"folder", "", "../up"}, {"file", "a.tex", "x"}}, "invalid_path"},
		"long folder":     {[]part{{"folder", "", strings.Repeat("a", 300)}, {"file", "a.tex", "x"}}, "invalid_body"},
		"wrong content":   {[]part{{"file", "a.png", "<svg/>"}}, "unsupported_file_type"},
		"unsupported":     {[]part{{"file", "a.docx", "PK"}}, "unsupported_file_type"},
		"reserved":        {[]part{{"file", "main.tex", "x"}}, "reserved_path"},
		"same name twice": {[]part{{"file", "a.tex", "x"}, {"file", "A.tex", "y"}}, "file_exists"},
		"too large":       {[]part{{"file", "a.pdf", "%PDF-" + strings.Repeat("x", MaxFileBytes)}}, "file_too_large"},
	}
	for name, c := range cases {
		store := &fakeStore{}
		w := serve(t, store, nil, "alice", multipartReq(t, base+"/files/upload", c.parts...))
		if got := decoded(t, w); got["code"] != c.code || store.uploaded != nil {
			t.Errorf("%s: %d %v", name, w.Code, got)
		}
	}
	w := serve(t, &fakeStore{}, nil, "alice", jsonReq("POST", base+"/files/upload", `{}`))
	if decoded(t, w)["code"] != "invalid_body" {
		t.Fatalf("a JSON body is not an upload: %s", w.Body)
	}
	broken := httptest.NewRequest("POST", base+"/files/upload", strings.NewReader("--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.tex\"\r\n\r\nunterminated"))
	broken.Header.Set("Content-Type", "multipart/form-data; boundary=b")
	if w := serve(t, &fakeStore{}, nil, "alice", broken); decoded(t, w)["code"] != "invalid_body" {
		t.Fatalf("%s", w.Body)
	}
}

func TestGetAndRawServeTheFileSafely(t *testing.T) {
	w := serve(t, &fakeStore{}, nil, "alice", jsonReq("GET", base+"/files/text", ""))
	if w.Code != http.StatusOK || decoded(t, w)["content"] != "Hello" {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	w = serve(t, &fakeStore{}, nil, "alice", jsonReq("GET", base+"/files/image/raw", ""))
	h := w.Header()
	if w.Code != http.StatusOK || h.Get("Content-Type") != "image/png" || h.Get("Content-Disposition") != "inline; filename=a.png" ||
		h.Get("X-Content-Type-Options") != "nosniff" || !strings.Contains(h.Get("Content-Security-Policy"), "sandbox") || !bytes.Equal(w.Body.Bytes(), pngBytes) {
		t.Fatalf("%d %v", w.Code, h)
	}
	w = serve(t, &fakeStore{}, nil, "alice", jsonReq("GET", base+"/files/pdf/raw", ""))
	if w.Header().Get("Content-Disposition") != "attachment; filename=paper.pdf" {
		t.Fatalf("%v", w.Header())
	}
	w = serve(t, &fakeStore{}, nil, "alice", jsonReq("GET", base+"/files/text/raw", ""))
	if w.Header().Get("Content-Type") != "text/x-tex; charset=utf-8" || w.Body.String() != "Hello" {
		t.Fatalf("%v %s", w.Header(), w.Body)
	}
	w = serve(t, &fakeStore{}, nil, "alice", jsonReq("GET", base+"/files/folder/raw", ""))
	if decoded(t, w)["code"] != "not_a_file" {
		t.Fatalf("%s", w.Body)
	}
	for _, path := range []string{"/files/x", "/files/x/raw"} {
		if w := serve(t, &fakeStore{err: ErrNotFound}, nil, "alice", jsonReq("GET", base+path, "")); w.Code != http.StatusNotFound {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
}

func TestSaveValidatesTheBody(t *testing.T) {
	store := &fakeStore{}
	w := serve(t, store, nil, "alice", jsonReq("PUT", base+"/files/f-1", `{"content":"New","base_version":3.0}`))
	if w.Code != http.StatusOK || decoded(t, w)["version"] != float64(4) || store.saved[0] != "f-1|New" {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	for body, code := range map[string]string{
		`{"content":"a"}`:                        "invalid_body",
		`{"content":"a","base_version":0}`:       "invalid_body",
		`{"content":"a","base_version":"1"}`:     "invalid_body",
		`{"content":"a","base_version":1.5}`:     "invalid_body",
		`{"base_version":1}`:                     "invalid_body",
		`{"content":"a","base_version":1,"x":1}`: "invalid_body",
		`{"content":"\u0007","base_version":1}`:  "invalid_text",
		`{"content":"` + strings.Repeat("a", MaxTextBytes+1) + `","base_version":1}`: "file_too_large",
	} {
		if got := decoded(t, serve(t, store, nil, "alice", jsonReq("PUT", base+"/files/f-1", body))); got["code"] != code {
			t.Errorf("%.60s: %v", body, got)
		}
	}
	if len(store.saved) != 1 {
		t.Fatal("invalid saves reached the store")
	}
}

func TestMoveAndDelete(t *testing.T) {
	store := &fakeStore{}
	if w := serve(t, store, nil, "alice", jsonReq("PATCH", base+"/files/f-1", `{"path":"sections"}`)); w.Code != http.StatusOK || store.moved != "sections" {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	for body, code := range map[string]string{`{}`: "invalid_body", `{"path":"main.tex"}`: "reserved_path", `{"path":".x"}`: "invalid_path", `x`: "invalid_body"} {
		if got := decoded(t, serve(t, store, nil, "alice", jsonReq("PATCH", base+"/files/f-1", body))); got["code"] != code {
			t.Errorf("%s: %v", body, got)
		}
	}
	if w := serve(t, store, nil, "alice", jsonReq("DELETE", base+"/files/f-9", "")); w.Code != http.StatusNoContent || store.deleted != "f-9" {
		t.Fatalf("%d", w.Code)
	}
	if w := serve(t, &fakeStore{err: ErrReadOnly}, nil, "alice", jsonReq("DELETE", base+"/files/f-9", "")); w.Code != http.StatusForbidden {
		t.Fatalf("%d", w.Code)
	}
}

func TestArchiveAndOutputDownloadAsAttachments(t *testing.T) {
	store := &fakeStore{bundle: Bundle{Title: "My: Paper", Main: "x", Output: pdfBytes}}
	w := serve(t, store, nil, "alice", jsonReq("GET", base+"/archive", ""))
	if w.Code != http.StatusOK || w.Header().Get("Content-Type") != "application/zip" ||
		w.Header().Get("Content-Disposition") != `attachment; filename="My Paper.zip"` || !bytes.HasPrefix(w.Body.Bytes(), []byte("PK")) {
		t.Fatalf("%d %v", w.Code, w.Header())
	}
	w = serve(t, store, nil, "alice", jsonReq("GET", base+"/output.pdf", ""))
	if w.Code != http.StatusOK || w.Header().Get("Content-Type") != "application/pdf" || w.Header().Get("Content-Disposition") != `attachment; filename="My Paper.pdf"` {
		t.Fatalf("%d %v", w.Code, w.Header())
	}
	for _, path := range []string{"/archive", "/output.pdf"} {
		if w := serve(t, &fakeStore{err: ErrNotFound}, nil, "alice", jsonReq("GET", base+path, "")); w.Code != http.StatusNotFound {
			t.Fatalf("%s %d", path, w.Code)
		}
	}
}

func TestCompileSendsTheProjectAndKeepsAProducedPDF(t *testing.T) {
	store := &fakeStore{bundle: Bundle{Main: `\input{a}`, Files: []Stored{{Path: "fig", Kind: KindFolder}, {Path: "a.tex", Kind: KindText, Data: []byte("A")}}}}
	compiler := &fakeCompiler{answer: `{"status":"compiled","pdf_base64":"` + base64.StdEncoding.EncodeToString(pdfBytes) + `","log":""}`}
	w := serve(t, store, compiler, "alice", jsonReq("POST", base+"/compile", ""))
	if w.Code != http.StatusOK || decoded(t, w)["status"] != "compiled" || !bytes.Equal(store.output, pdfBytes) {
		t.Fatalf("%d %s %q", w.Code, w.Body, store.output)
	}
	var sent compileRequest
	if err := json.Unmarshal(compiler.body, &sent); err != nil {
		t.Fatal(err)
	}
	if sent.LatexSource != `\input{a}` || len(sent.Files) != 1 || sent.Files[0].Path != "a.tex" || sent.Files[0].ContentBase64 != "QQ==" {
		t.Fatalf("folders are not sent; files are base64: %+v", sent)
	}

	failed := &fakeStore{}
	w = serve(t, failed, &fakeCompiler{answer: `{"status":"error","log":"x","errors":["line 1: Undefined"]}`}, "alice", jsonReq("POST", base+"/compile", ""))
	if w.Code != http.StatusOK || failed.output != nil {
		t.Fatalf("a failed compile keeps nothing: %d %q", w.Code, failed.output)
	}
	w = serve(t, &fakeStore{}, &fakeCompiler{fail: true}, "alice", jsonReq("POST", base+"/compile", ""))
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("the compiler's own refusal stands: %d", w.Code)
	}
	w = serve(t, &fakeStore{err: ErrNotFound}, &fakeCompiler{}, "alice", jsonReq("POST", base+"/compile", ""))
	if w.Code != http.StatusNotFound {
		t.Fatalf("%d", w.Code)
	}
}

func TestImportCreatesAWorkspaceFromAZip(t *testing.T) {
	store := &fakeStore{}
	archive := zipOf(t, map[string]string{"main.tex": `\documentclass{article}`, "refs.bib": "@a{}", "notes.docx": "PK"})
	w := serve(t, store, nil, "alice", multipartReq(t, "/api/v1/workspaces/import", part{"file", "Thesis.zip", string(archive)}))
	got := decoded(t, w)
	if w.Code != http.StatusCreated || got["title"] != "Thesis" || got["role"] != "owner" || w.Header().Get("Location") != "/api/v1/workspaces/ws-new" {
		t.Fatalf("%d %v", w.Code, got)
	}
	if skipped := got["skipped"].([]any); len(skipped) != 1 || skipped[0] != "notes.docx" {
		t.Fatalf("%v", got["skipped"])
	}
	if store.imported.Main != `\documentclass{article}` || len(store.imported.Files) != 1 {
		t.Fatalf("%+v", store.imported)
	}
	w = serve(t, store, nil, "alice", multipartReq(t, "/api/v1/workspaces/import", part{"title", "", "Named"}, part{"file", "x.zip", string(archive)}))
	if decoded(t, w)["title"] != "Named" {
		t.Fatalf("%s", w.Body)
	}
}

func TestImportRefusals(t *testing.T) {
	good := string(zipOf(t, map[string]string{"main.tex": "x"}))
	cases := map[string]struct {
		parts []part
		code  string
	}{
		"no file":        {[]part{{"title", "", "x"}}, "invalid_body"},
		"two files":      {[]part{{"file", "a.zip", good}, {"file", "b.zip", good}}, "invalid_body"},
		"unknown field":  {[]part{{"x", "", "y"}}, "invalid_body"},
		"not a zip":      {[]part{{"file", "a.zip", "hello"}}, "invalid_archive"},
		"no main":        {[]part{{"file", "a.zip", string(zipOf(t, map[string]string{"a.md": "x"}))}}, "no_main_file"},
		"bad title":      {[]part{{"title", "", "a\x07"}, {"file", "a.zip", good}}, "invalid_title"},
		"long title":     {[]part{{"title", "", strings.Repeat("a", 2000)}, {"file", "a.zip", good}}, "invalid_title"},
		"main too large": {[]part{{"file", "a.zip", string(zipOf(t, map[string]string{"main.tex": strings.Repeat("a", maxMainRunes+1)}))}}, "main_too_large"},
	}
	for name, c := range cases {
		store := &fakeStore{}
		w := serve(t, store, nil, "alice", multipartReq(t, "/api/v1/workspaces/import", c.parts...))
		if got := decoded(t, w); got["code"] != c.code || store.imported != nil {
			t.Errorf("%s: %d %v", name, w.Code, got)
		}
	}
	if w := serve(t, &fakeStore{}, nil, "alice", jsonReq("POST", "/api/v1/workspaces/import", "{}")); decoded(t, w)["code"] != "invalid_body" {
		t.Fatal(w.Body)
	}
	w := serve(t, &fakeStore{err: workspace.ErrLimitReached}, nil, "alice", multipartReq(t, "/api/v1/workspaces/import", part{"file", "a.zip", good}))
	if decoded(t, w)["code"] != "workspace_limit_reached" {
		t.Fatal(w.Body)
	}
}

func TestBodyErrorsReportTooLarge(t *testing.T) {
	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	bodyError(c, &http.MaxBytesError{Limit: 1})
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatal(w.Code)
	}
	if errors.Is(errNotZip, errNoMain) {
		t.Fatal("distinct errors")
	}
}

func TestFixedSegmentsAnswerOtherMethods405(t *testing.T) {
	for _, route := range [][2]string{
		{"GET", base + "/files/upload"}, {"PUT", base + "/files/upload"}, {"PATCH", base + "/files/upload"},
		{"DELETE", base + "/files/upload"}, {"GET", "/api/v1/workspaces/import"}, {"PATCH", "/api/v1/workspaces/import"},
	} {
		store := &fakeStore{}
		w := serve(t, store, nil, "alice", jsonReq(route[0], route[1], `{"content":"a","base_version":1,"path":"x.tex"}`))
		if w.Code != http.StatusMethodNotAllowed || w.Header().Get("Allow") != "POST" || !strings.Contains(w.Body.String(), "method_not_allowed") {
			t.Fatalf("%s %s: %d %v %s", route[0], route[1], w.Code, w.Header(), w.Body)
		}
	}
	// A file's raw content is still reached past the fixed segment's sibling.
	w := serve(t, &fakeStore{}, nil, "alice", jsonReq("GET", base+"/files/upload/raw", ""))
	if w.Code == http.StatusMethodNotAllowed {
		t.Fatalf("raw: %d %s", w.Code, w.Body)
	}
}
