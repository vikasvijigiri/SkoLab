package ai

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// ── A store in memory ──────────────────────────────────────────────────────

type memReports struct {
	mu      sync.Mutex
	roles   map[string]string // workspace/user -> role
	source  string
	version int
	reports map[string]*Report
	failing bool
}

func newReports() *memReports {
	return &memReports{roles: map[string]string{}, reports: map[string]*Report{}}
}

func (s *memReports) Role(_ context.Context, ws, uid string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failing {
		return "", ErrUnavailable
	}
	role, ok := s.roles[ws+"/"+uid]
	if !ok {
		return "", ErrNotFound
	}
	return role, nil
}

func (s *memReports) Source(context.Context, string) (string, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.source, s.version, nil
}

func (s *memReports) Create(_ context.Context, ws, _ string, version int) (Report, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range s.reports {
		if r.WorkspaceID == ws && (r.Status == "queued" || r.Status == "running") {
			return *r, false, nil
		}
	}
	r := &Report{ID: uuid.NewString(), WorkspaceID: ws, Status: "queued", DocumentVersion: version, CreatedAt: time.Now().UTC()}
	s.reports[r.ID] = r
	return *r, true, nil
}

func (s *memReports) Get(_ context.Context, ws, id string) (Report, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.reports[id]
	if !ok || r.WorkspaceID != ws {
		return Report{}, ErrNotFound
	}
	return *r, nil
}

func (s *memReports) List(_ context.Context, ws string, _ int) ([]Report, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []Report
	for _, r := range s.reports {
		if r.WorkspaceID == ws {
			out = append(out, *r)
		}
	}
	return out, nil
}

func (s *memReports) set(id string, change func(*Report)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.reports[id]
	if !ok {
		return ErrNotFound
	}
	change(r)
	return nil
}

func (s *memReports) Start(_ context.Context, id string) error {
	return s.set(id, func(r *Report) { r.Status = "running" })
}

func (s *memReports) Finish(_ context.Context, id string, result Result) error {
	return s.set(id, func(r *Report) { r.Status, r.Score, r.Result = "done", &result.Score, &result })
}

func (s *memReports) Fail(_ context.Context, id, reason string) error {
	return s.set(id, func(r *Report) { r.Status, r.Error = "failed", &reason })
}

func (s *memReports) Interrupted(context.Context, time.Duration) (int64, error) { return 0, nil }

// ── The model service ──────────────────────────────────────────────────────

type fakeModel struct {
	status  int
	body    string
	retry   string
	calls   int
	lastReq map[string]string
	token   string
}

func (f *fakeModel) server(t *testing.T) *httptest.Server {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls++
		f.token = r.Header.Get("X-Internal-Token")
		data, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(data, &f.lastReq)
		if r.URL.Path != "/api/v1/ai/improve" {
			http.NotFound(w, r)
			return
		}
		if f.retry != "" {
			w.Header().Set("Retry-After", f.retry)
		}
		w.WriteHeader(f.status)
		_, _ = w.Write([]byte(f.body))
	}))
	t.Cleanup(server.Close)
	return server
}

const ws = "11111111-1111-1111-1111-111111111111"

type harness struct {
	router    *gin.Engine
	reports   *memReports
	budgets   *memBudgets
	model     *fakeModel
	works     []Work
	searchErr error
}

func newHarness(t *testing.T, configure ...func(*Config)) *harness {
	t.Helper()
	gin.SetMode(gin.TestMode)
	h := &harness{reports: newReports(), budgets: newBudgets(), model: &fakeModel{status: 200, body: `{"edits":[],"model":"m"}`}}
	h.reports.roles[ws+"/ada"] = "owner"
	h.reports.roles[ws+"/bo"] = "editor"
	h.reports.roles[ws+"/cy"] = "viewer"
	cfg := Config{ImproveEnabled: true, OriginalityEnabled: true, ImprovePerUser: 3, OriginalityPerUser: 2, ModelDaily: 100, SearchDaily: 100, MaxProbes: 4}
	for _, f := range configure {
		f(&cfg)
	}
	model := h.model.server(t)
	search := func(ctx context.Context, q string) ([]Work, error) { return h.works, h.searchErr }
	runner := NewRunner(h.reports, search, cfg.MaxProbes, 1)
	d := Deps{Client: model.Client(), PythonURL: model.URL, Token: "gateway-secret", Config: cfg, Reports: h.reports, Budgets: h.budgets, Runner: runner}
	r := gin.New()
	g := r.Group("/api/v1")
	g.Use(func(c *gin.Context) {
		if uid := c.GetHeader("X-Test-User"); uid != "" {
			c.Set("user_id", uid)
		}
	})
	Register(g, d)
	h.router = r
	return h
}

func (h *harness) do(t *testing.T, uid, method, path, body string) (*httptest.ResponseRecorder, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if uid != "" {
		req.Header.Set("X-Test-User", uid)
	}
	w := httptest.NewRecorder()
	h.router.ServeHTTP(w, req)
	var out map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w, out
}

func improveBody(text, goal string) string {
	b, _ := json.Marshal(map[string]string{"text": text, "goal": goal})
	return string(b)
}

const passage = `The results \cite{k20} was very clear and we obsereved large gains when $T<T_c$.`

// ── Improve ────────────────────────────────────────────────────────────────

func TestImproveMasksAsksAndShowsOnlyVerifiedEdits(t *testing.T) {
	h := newHarness(t)
	h.model.body = `{"model":"llama","edits":[
		{"original":"The results ⟦1⟧ was very clear","replacement":"The results ⟦1⟧ were very clear","reason":"Agreement","category":"grammar"},
		{"original":"obsereved","replacement":"observed","category":"grammar"},
		{"original":"when ⟦2⟧","replacement":"below the critical temperature","category":"clarity"}]}`
	w, body := h.do(t, "bo", "POST", "/api/v1/workspaces/"+ws+"/ai/improve", improveBody(passage, "grammar"))
	if w.Code != 200 {
		t.Fatalf("%d %v", w.Code, body)
	}
	sent := h.model.lastReq["text"]
	if strings.Contains(sent, `\cite`) || strings.Contains(sent, "$") || h.model.lastReq["goal"] != "grammar" || h.model.token != "gateway-secret" {
		t.Fatalf("the model saw %q (token %q)", sent, h.model.token)
	}
	suggestions := body["suggestions"].([]any)
	if len(suggestions) != 2 || body["model"] != "llama" || body["left_today"] != float64(2) {
		t.Fatalf("%v", body)
	}
	first := suggestions[0].(map[string]any)
	if first["original"] != `The results \cite{k20} was very clear` || first["replacement"] != `The results \cite{k20} were very clear` || first["start"] != float64(0) {
		t.Fatalf("%v", first)
	}
	if h.budgets.used[modelKey] != 1 || h.budgets.used[userKey("bo", "improve")] != 1 {
		t.Fatalf("%v", h.budgets.used)
	}
}

func TestImproveRefusals(t *testing.T) {
	h := newHarness(t)
	cases := []struct {
		uid, path, body string
		status          int
		code            string
	}{
		{"", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody(passage, "grammar"), 401, "unauthenticated"},
		{"bo", "/api/v1/workspaces/" + ws + "/ai/improve", `{"text":"x"`, 400, "invalid_body"},
		{"bo", "/api/v1/workspaces/" + ws + "/ai/improve", `{"text":"x","goal":"grammar","extra":1}`, 400, "invalid_body"},
		{"bo", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody(passage, "poetry"), 400, "invalid_goal"},
		{"bo", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody("   ", "grammar"), 400, "invalid_text"},
		{"bo", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody("nul\x00", "grammar"), 400, "invalid_text"},
		{"bo", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody(strings.Repeat("é", 4001), "grammar"), 413, "passage_too_long"},
		{"cy", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody(passage, "grammar"), 403, "edit_forbidden"},
		{"eve", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody(passage, "grammar"), 404, "not_found"},
		{"bo", "/api/v1/workspaces/" + ws + "/ai/improve", improveBody(`$E=mc^2$ \cite{x} ok`, "grammar"), 422, "nothing_to_improve"},
	}
	for _, c := range cases {
		w, body := h.do(t, c.uid, "POST", c.path, c.body)
		if w.Code != c.status || body["code"] != c.code {
			t.Errorf("%s %q: %d %v", c.uid, c.body[:min(len(c.body), 40)], w.Code, body)
		}
	}
	if h.model.calls != 0 || len(h.budgets.used) != 0 {
		t.Fatalf("refused requests reached the model or the budget: %d %v", h.model.calls, h.budgets.used)
	}
}

func TestImproveAllowancesAndProviderFailuresAreRefunded(t *testing.T) {
	h := newHarness(t)
	path := "/api/v1/workspaces/" + ws + "/ai/improve"
	for _, failure := range []struct {
		status int
		retry  string
		want   int
		code   string
	}{
		{429, "12", 503, "ai_busy"},
		{502, "", 502, "ai_failed"},
		{503, "", 503, "ai_unavailable"},
		{403, "", 503, "ai_unavailable"},
		{200, "", 502, "ai_failed"}, // a 200 that is not JSON
	} {
		h.model.status, h.model.retry, h.model.body = failure.status, failure.retry, "not json"
		w, body := h.do(t, "ada", "POST", path, improveBody(passage, "clarity"))
		if w.Code != failure.want || body["code"] != failure.code || (failure.retry != "" && w.Header().Get("Retry-After") != failure.retry) {
			t.Fatalf("%d: %d %v", failure.status, w.Code, body)
		}
	}
	if h.budgets.used[userKey("ada", "improve")] != 0 || h.budgets.used[modelKey] != 0 {
		t.Fatalf("failed calls must be refunded: %v", h.budgets.used)
	}
	h.model.status, h.model.body = 200, `{"edits":[],"model":"m"}`
	for i := 0; i < 3; i++ {
		if w, body := h.do(t, "ada", "POST", path, improveBody(passage, "clarity")); w.Code != 200 {
			t.Fatalf("%d %v", w.Code, body)
		}
	}
	w, body := h.do(t, "ada", "POST", path, improveBody(passage, "clarity"))
	if w.Code != 429 || body["code"] != "ai_daily_limit" || w.Header().Get("Retry-After") == "" {
		t.Fatalf("%d %v", w.Code, body)
	}
	// The shared model budget runs out for everyone; the person's charge is undone.
	h.budgets.used[modelKey] = 100
	w, body = h.do(t, "bo", "POST", path, improveBody(passage, "clarity"))
	if w.Code != 429 || body["code"] != "ai_capacity" || h.budgets.used[userKey("bo", "improve")] != 0 {
		t.Fatalf("%d %v %v", w.Code, body, h.budgets.used)
	}
	h.budgets.down = true
	if w, body := h.do(t, "bo", "POST", path, improveBody(passage, "clarity")); w.Code != 503 || body["code"] != "unavailable" {
		t.Fatalf("%d %v", w.Code, body)
	}
}

func TestImproveWhenTheModelIsNotConfiguredOrTheStoreIsDown(t *testing.T) {
	h := newHarness(t, func(c *Config) { c.ImproveEnabled = false })
	w, body := h.do(t, "ada", "POST", "/api/v1/workspaces/"+ws+"/ai/improve", improveBody(passage, "clarity"))
	if w.Code != 503 || body["code"] != "ai_unconfigured" || h.model.calls != 0 {
		t.Fatalf("%d %v", w.Code, body)
	}
	h = newHarness(t)
	h.reports.failing = true
	if w, body := h.do(t, "ada", "POST", "/api/v1/workspaces/"+ws+"/ai/improve", improveBody(passage, "clarity")); w.Code != 503 || body["code"] != "unavailable" {
		t.Fatalf("%d %v", w.Code, body)
	}
	// The model service unreachable.
	h = newHarness(t)
	h.router = gin.New()
	d := Deps{Client: http.DefaultClient, PythonURL: "http://127.0.0.1:1", Config: Config{ImproveEnabled: true, ImprovePerUser: 5, ModelDaily: 5}, Reports: h.reports, Budgets: h.budgets}
	g := h.router.Group("/api/v1")
	g.Use(func(c *gin.Context) { c.Set("user_id", "ada") })
	Register(g, d)
	if w, body := h.do(t, "ada", "POST", "/api/v1/workspaces/"+ws+"/ai/improve", improveBody(passage, "clarity")); w.Code != 503 || body["code"] != "ai_unavailable" || h.budgets.used[modelKey] != 0 {
		t.Fatalf("%d %v", w.Code, body)
	}
}

// ── Status ─────────────────────────────────────────────────────────────────

func TestStatusShowsWhatIsLeft(t *testing.T) {
	h := newHarness(t)
	h.budgets.used[userKey("ada", "improve")] = 1
	h.budgets.used[searchKey] = 90 // 10 searches left: two checks of four
	w, body := h.do(t, "ada", "GET", "/api/v1/ai/status", "")
	improve, originality := body["improve"].(map[string]any), body["originality"].(map[string]any)
	if w.Code != 200 || improve["left_today"] != float64(2) || improve["daily_limit"] != float64(3) || originality["left_today"] != float64(2) || improve["available"] != true {
		t.Fatalf("%d %v", w.Code, body)
	}
	h = newHarness(t, func(c *Config) { c.ImproveEnabled, c.OriginalityEnabled = false, false })
	_, body = h.do(t, "ada", "GET", "/api/v1/ai/status", "")
	if body["improve"].(map[string]any)["available"] != false || body["originality"].(map[string]any)["left_today"] != float64(0) {
		t.Fatalf("%v", body)
	}
	if w, _ := h.do(t, "", "GET", "/api/v1/ai/status", ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
}

// ── Originality ────────────────────────────────────────────────────────────

func TestOriginalityCheckRunsInTheBackgroundAndIsReadable(t *testing.T) {
	h := newHarness(t)
	h.reports.source = texDocument(copied + ". " + strings.Repeat("Our own original wording follows in this long sentence today. ", 3))
	h.reports.version = 7
	h.works = []Work{{ID: "W1", Title: "Source", Abstract: copied}}
	base := "/api/v1/workspaces/" + ws + "/ai/originality"
	w, body := h.do(t, "bo", "POST", base, "")
	if w.Code != 202 {
		t.Fatalf("%d %v", w.Code, body)
	}
	report := body["report"].(map[string]any)
	id := report["id"].(string)
	if w.Header().Get("Location") != base+"/"+id || report["document_version"] != float64(7) || body["left_today"] != float64(1) {
		t.Fatalf("%v %v", w.Header(), body)
	}
	var got map[string]any
	for i := 0; i < 200; i++ {
		_, got = h.do(t, "cy", "GET", base+"/"+id, "")
		if got["status"] == "done" || got["status"] == "failed" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if got["status"] != "done" || got["score"].(float64) <= 0 {
		t.Fatalf("%v", got)
	}
	result := got["result"].(map[string]any)
	if len(result["matches"].([]any)) != 1 || result["sources"].([]any)[0].(map[string]any)["id"] != "W1" {
		t.Fatalf("%v", result)
	}
	_, list := h.do(t, "cy", "GET", base, "")
	if len(list["reports"].([]any)) != 1 {
		t.Fatalf("%v", list)
	}
}

func TestOriginalityRefusalsAndFailures(t *testing.T) {
	h := newHarness(t)
	base := "/api/v1/workspaces/" + ws + "/ai/originality"
	for _, c := range []struct {
		uid, method, path string
		status            int
		code              string
	}{
		{"cy", "POST", base, 403, "edit_forbidden"},
		{"eve", "GET", base, 404, "not_found"},
		{"ada", "POST", base + "?x=1", 400, "unknown_parameter"},
		{"ada", "GET", base + "?x=1", 400, "unknown_parameter"},
		{"ada", "GET", base + "/" + uuid.NewString() + "?x=1", 400, "unknown_parameter"},
		{"ada", "GET", base + "/not-a-uuid", 404, "not_found"},
		{"ada", "GET", base + "/" + uuid.NewString(), 404, "not_found"},
		{"", "POST", base, 401, "unauthenticated"},
	} {
		if w, body := h.do(t, c.uid, c.method, c.path, ""); w.Code != c.status || body["code"] != c.code {
			t.Errorf("%s %s %s: %d %v", c.uid, c.method, c.path, w.Code, body)
		}
	}

	// Too little prose fails the report with a reason, not the request.
	h.reports.source = texDocument("Too short.")
	_, body := h.do(t, "ada", "POST", base, "")
	id := body["report"].(map[string]any)["id"].(string)
	var got map[string]any
	for i := 0; i < 200; i++ {
		if _, got = h.do(t, "ada", "GET", base+"/"+id, ""); got["status"] == "failed" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if got["status"] != "failed" || !strings.Contains(got["error"].(string), "too little prose") {
		t.Fatalf("%v", got)
	}

	// The daily allowance (2): the failed check counted, one more is allowed.
	h.reports.source = texDocument(strings.Repeat("Enough words to check are written in this sentence for the test. ", 5))
	h.searchErr = errors.New("index down")
	if w, _ := h.do(t, "ada", "POST", base, ""); w.Code != 202 {
		t.Fatal(w.Code)
	}
	for i := 0; i < 200; i++ {
		reports, _ := h.reports.List(context.Background(), ws, 10)
		running := false
		for _, r := range reports {
			running = running || r.Status == "queued" || r.Status == "running"
		}
		if !running {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if w, body := h.do(t, "ada", "POST", base, ""); w.Code != 429 || body["code"] != "ai_daily_limit" {
		t.Fatalf("%d %v", w.Code, body)
	}
}

func TestOriginalityDoesNotQueueTwiceOrRunPastCapacity(t *testing.T) {
	h := newHarness(t)
	base := "/api/v1/workspaces/" + ws + "/ai/originality"
	// A check already queued is answered without charging.
	queued, _, _ := h.reports.Create(context.Background(), ws, "bo", 1)
	w, body := h.do(t, "ada", "POST", base, "")
	if w.Code != 202 || body["report"].(map[string]any)["id"] != queued.ID || h.budgets.used[userKey("ada", "originality")] != 0 || body["left_today"] != float64(2) {
		t.Fatalf("%d %v %v", w.Code, body, h.budgets.used)
	}
	h.budgets.used[searchKey] = 100
	if w, body := h.do(t, "bo", "POST", base, ""); w.Code != 429 || body["code"] != "ai_capacity" {
		t.Fatalf("%d %v", w.Code, body)
	}
	h = newHarness(t, func(c *Config) { c.OriginalityEnabled = false })
	if w, body := h.do(t, "ada", "POST", base, ""); w.Code != 503 || body["code"] != "ai_unconfigured" {
		t.Fatalf("%d %v", w.Code, body)
	}
}

func TestRunnerSurvivesAPanickingSearch(t *testing.T) {
	store := newReports()
	store.source = texDocument(strings.Repeat("Enough words to check are written in this sentence for the test. ", 5))
	report, _, _ := store.Create(context.Background(), ws, "ada", 1)
	runner := NewRunner(store, func(context.Context, string) ([]Work, error) { panic("boom") }, 3, 1)
	var done sync.WaitGroup
	done.Add(1)
	runner.Submit(report, done.Done)
	done.Wait()
	got, _ := store.Get(context.Background(), ws, report.ID)
	if got.Status != "failed" || got.Error == nil {
		t.Fatalf("%+v", got)
	}
}
