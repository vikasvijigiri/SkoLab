package files

import (
	"context"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/workspace"
)

// Integration tests against the real schema. CI provides TEST_DATABASE_URL
// (the same Postgres the Python suite bootstraps); elsewhere they skip.
func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func newUser(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	id := "test-" + uuid.NewString()
	if _, err := pool.Exec(context.Background(), "INSERT INTO users (id, display_name) VALUES ($1, 'Test')", id); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx := context.Background()
		_, _ = pool.Exec(ctx, "DELETE FROM workspaces WHERE owner_id = $1", id)
		_, _ = pool.Exec(ctx, "DELETE FROM users WHERE id = $1", id)
	})
	return id
}

// newWorkspace creates a workspace owned by owner with members (user -> role).
func newWorkspace(t *testing.T, pool *pgxpool.Pool, owner string, members map[string]string) string {
	t.Helper()
	ctx, id := context.Background(), uuid.NewString()
	if _, err := pool.Exec(ctx, "INSERT INTO workspaces (id, owner_id, title, created_at) VALUES ($1, $2, 'Paper', now())", id, owner); err != nil {
		t.Fatal(err)
	}
	for user, role := range members {
		if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, $3, 'active', now())", id, user, role); err != nil {
			t.Fatal(err)
		}
	}
	return id
}

func text(p, content string) NewFile {
	f, err := newText(p, content)
	if err != nil {
		panic(err)
	}
	return f
}

func paths(l Listing) string {
	var out []string
	for _, f := range l.Files {
		out = append(out, f.Path)
	}
	return strings.Join(out, ",")
}

func TestPostgres_CreateMakesTheFoldersAboveAFile(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	f, err := store.Create(ctx, ws, owner, text("chapters/one/intro.tex", "Hello"))
	if err != nil || f.Version != 1 || *f.Content != "Hello" || f.Size != 5 || f.UpdatedBy == nil || *f.UpdatedBy != owner {
		t.Fatalf("%+v %v", f, err)
	}
	if _, err := store.Create(ctx, ws, owner, text("Chapters/One/INTRO.tex", "")); !errors.Is(err, ErrExists) {
		t.Fatalf("paths are unique ignoring case: %v", err)
	}
	if _, err := store.Create(ctx, ws, owner, text("chapters/one/intro.tex/x.tex", "")); !errors.Is(err, ErrExists) {
		t.Fatalf("a file cannot be a folder: %v", err)
	}
	l, err := store.List(ctx, ws, owner)
	if err != nil || paths(l) != "chapters,chapters/one,chapters/one/intro.tex" || l.Usage.Bytes != 5 || l.Usage.Entries != 3 || l.Role != "owner" {
		t.Fatalf("%+v %v", l, err)
	}
	if l.Main.Version != 0 || l.Main.UpdatedAt != nil || l.Output != nil {
		t.Fatalf("a never-saved main.tex and no output: %+v", l)
	}
}

func TestPostgres_RolesDecideWhoReadsAndWhoWrites(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner, editor, viewer, outsider := newUser(t, pool), newUser(t, pool), newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, pool, owner, map[string]string{editor: "editor", viewer: "viewer"})

	f, err := store.Create(ctx, ws, editor, text("a.tex", "x"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Create(ctx, ws, viewer, text("b.tex", "x")); !errors.Is(err, ErrReadOnly) {
		t.Fatalf("viewer: %v", err)
	}
	if err := store.Delete(ctx, ws, viewer, f.ID); !errors.Is(err, ErrReadOnly) {
		t.Fatalf("viewer: %v", err)
	}
	if _, _, err := store.Get(ctx, ws, viewer, f.ID); err != nil {
		t.Fatalf("a viewer reads: %v", err)
	}
	if _, err := store.List(ctx, ws, outsider); !errors.Is(err, ErrNotFound) {
		t.Fatalf("outsider: %v", err)
	}
	if _, _, err := store.Get(ctx, ws, outsider, f.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("outsider: %v", err)
	}
	if _, _, err := store.Get(ctx, ws, owner, uuid.NewString()); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing file: %v", err)
	}
	other := newWorkspace(t, pool, owner, nil)
	if _, _, err := store.Get(ctx, other, owner, f.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("a file belongs to its own workspace: %v", err)
	}
}

func TestPostgres_UploadReplacesOnlyWhenAsked(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	png, _ := classify("fig/a.png", pngBytes)
	out, err := store.Upload(ctx, ws, owner, []NewFile{png, text("refs.bib", "@a{}")}, false)
	if err != nil || len(out) != 2 || out[0].Kind != KindBinary || out[1].Content != nil {
		t.Fatalf("%+v %v", out, err)
	}
	if _, err := store.Upload(ctx, ws, owner, []NewFile{text("refs.bib", "@b{}")}, false); !errors.Is(err, ErrExists) {
		t.Fatalf("%v", err)
	}
	again, err := store.Upload(ctx, ws, owner, []NewFile{text("refs.bib", "@b{}")}, true)
	if err != nil || again[0].Version != 2 || again[0].ID != out[1].ID {
		t.Fatalf("%+v %v", again, err)
	}
	if _, err := store.Upload(ctx, ws, owner, []NewFile{{Path: "fig", Kind: KindText, ContentType: "text/plain"}}, true); !errors.Is(err, ErrExists) {
		t.Fatalf("a folder is never replaced: %v", err)
	}
	f, data, err := store.Get(ctx, ws, owner, out[0].ID)
	if err != nil || string(data) != string(pngBytes) || f.Content != nil {
		t.Fatalf("%+v %v", f, err)
	}
	f, data, err = store.Get(ctx, ws, owner, out[1].ID)
	if err != nil || string(data) != "@b{}" || *f.Content != "@b{}" {
		t.Fatalf("%+v %q %v", f, data, err)
	}
}

func TestPostgres_ProjectLimitsHold(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	big := make([]byte, MaxFileBytes)
	copy(big, "%PDF-")
	one, _ := classify("a.pdf", big)
	two, _ := classify("b.pdf", big)
	if _, err := store.Upload(ctx, ws, owner, []NewFile{one, two}, false); err != nil {
		t.Fatalf("exactly 10 MiB fits: %v", err)
	}
	if _, err := store.Create(ctx, ws, owner, text("c.tex", "x")); !errors.Is(err, ErrFull) {
		t.Fatalf("one byte more does not: %v", err)
	}
	if _, err := store.Create(ctx, ws, owner, NewFile{Path: "empty", Kind: KindFolder}); err != nil {
		t.Fatalf("an empty folder still fits: %v", err)
	}

	ws2 := newWorkspace(t, pool, owner, nil)
	var many []NewFile
	for i := 0; i < MaxEntries; i++ {
		many = append(many, NewFile{Path: "d" + uuid.NewString()[:8], Kind: KindFolder})
	}
	if _, err := store.Upload(ctx, ws2, owner, many, false); err != nil {
		t.Fatalf("200 entries fit: %v", err)
	}
	if _, err := store.Create(ctx, ws2, owner, NewFile{Path: "one-more", Kind: KindFolder}); !errors.Is(err, ErrFull) {
		t.Fatalf("201 do not: %v", err)
	}
	if _, err := store.Create(ctx, ws2, owner, text("x/y.tex", "")); !errors.Is(err, ErrFull) {
		t.Fatalf("folders a file needs count too: %v", err)
	}
}

func TestPostgres_SaveTextNeedsTheCurrentVersion(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	f, _ := store.Create(ctx, ws, owner, text("a.tex", "one"))
	saved, err := store.SaveText(ctx, ws, owner, f.ID, "two", 1)
	if err != nil || saved.Version != 2 || saved.Size != 3 {
		t.Fatalf("%+v %v", saved, err)
	}
	var conflict *ConflictError
	if _, err := store.SaveText(ctx, ws, owner, f.ID, "stale", 1); !errors.As(err, &conflict) || conflict.Current != 2 {
		t.Fatalf("%v", err)
	}
	folder, _ := store.Create(ctx, ws, owner, NewFile{Path: "fig", Kind: KindFolder})
	if _, err := store.SaveText(ctx, ws, owner, folder.ID, "x", 1); !errors.Is(err, ErrNotText) {
		t.Fatalf("%v", err)
	}
	if _, err := store.SaveText(ctx, ws, owner, uuid.NewString(), "x", 1); !errors.Is(err, ErrNotFound) {
		t.Fatalf("%v", err)
	}

	// Two saves from version 2 at once: exactly one wins.
	var wg sync.WaitGroup
	results := make(chan error, 2)
	for _, content := range []string{"left", "right"} {
		wg.Add(1)
		go func(content string) {
			defer wg.Done()
			_, err := store.SaveText(ctx, ws, owner, f.ID, content, 2)
			results <- err
		}(content)
	}
	wg.Wait()
	close(results)
	won := 0
	for err := range results {
		if err == nil {
			won++
		} else if !errors.As(err, &conflict) {
			t.Fatal(err)
		}
	}
	if won != 1 {
		t.Fatalf("%d saves won", won)
	}
}

func TestPostgres_MovingAFolderMovesItsContents(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	if _, err := store.Create(ctx, ws, owner, text("sec_1/a/x.tex", "x")); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Create(ctx, ws, owner, text("sec_10/y.tex", "y")); err != nil {
		t.Fatal(err)
	}
	l, _ := store.List(ctx, ws, owner)
	folder := l.Files[0] // sec_1
	if folder.Path != "sec_1" {
		t.Fatal(paths(l))
	}
	moved, err := store.Move(ctx, ws, owner, folder.ID, "parts/one")
	if err != nil || moved.Path != "parts/one" {
		t.Fatalf("%+v %v", moved, err)
	}
	l, _ = store.List(ctx, ws, owner)
	// "_" is a LIKE wildcard; sec_10 must stay where it is.
	if got := paths(l); got != "parts,parts/one,parts/one/a,parts/one/a/x.tex,sec_10,sec_10/y.tex" {
		t.Fatal(got)
	}
	if _, err := store.Move(ctx, ws, owner, moved.ID, "parts/one/a/inside"); !errors.Is(err, ErrInvalidMove) {
		t.Fatalf("into itself: %v", err)
	}
	if _, err := store.Move(ctx, ws, owner, moved.ID, "sec_10"); !errors.Is(err, ErrExists) {
		t.Fatalf("onto another folder: %v", err)
	}
	if _, err := store.Move(ctx, ws, owner, moved.ID, "a/b/c/d/e"); !errors.Is(err, ErrInvalidMove) {
		t.Fatalf("children would be too deep: %v", err)
	}
	if _, err := store.Move(ctx, ws, owner, moved.ID, "Parts/One"); err != nil {
		t.Fatalf("a change of case is a rename: %v", err)
	}
	if _, err := store.Move(ctx, ws, owner, uuid.NewString(), "z"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestPostgres_RenamingKeepsAFilesType(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	notes, err := store.Create(ctx, ws, owner, text("notes.txt", "@a{}"))
	if err != nil {
		t.Fatal(err)
	}
	png, _ := classify("a.png", pngBytes)
	img, err := store.Create(ctx, ws, owner, png)
	if err != nil {
		t.Fatal(err)
	}
	renamed, err := store.Move(ctx, ws, owner, notes.ID, "refs.bib")
	if err != nil || renamed.ContentType != "text/x-bibtex" {
		t.Fatalf("text to text: %+v %v", renamed, err)
	}
	for _, c := range []struct{ id, target string }{
		{notes.ID, "refs.png"}, {img.ID, "a.jpg"}, {img.ID, "a.tex"}, {img.ID, "a.docx"},
	} {
		if _, err := store.Move(ctx, ws, owner, c.id, c.target); !errors.Is(err, ErrTypeChange) {
			t.Fatalf("%s: %v", c.target, err)
		}
	}
	if moved, err := store.Move(ctx, ws, owner, img.ID, "fig/A.PNG"); err != nil || moved.ContentType != "image/png" {
		t.Fatalf("same type: %+v %v", moved, err)
	}
}

func TestPostgres_DeletingAFolderDeletesItsContents(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	_, _ = store.Create(ctx, ws, owner, text("fig_a/x.tex", "x"))
	_, _ = store.Create(ctx, ws, owner, text("figXa/y.tex", "y"))
	l, _ := store.List(ctx, ws, owner)
	if err := store.Delete(ctx, ws, owner, l.Files[0].ID); err != nil {
		t.Fatal(err)
	}
	l, _ = store.List(ctx, ws, owner)
	if got := paths(l); got != "figXa,figXa/y.tex" {
		t.Fatal(got)
	}
	if err := store.Delete(ctx, ws, owner, l.Files[0].ID); err != nil {
		t.Fatal(err)
	}
	if err := store.Delete(ctx, ws, owner, l.Files[0].ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestPostgres_BundleOutputAndListing(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner, viewer := newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, pool, owner, map[string]string{viewer: "viewer"})
	if _, err := pool.Exec(ctx, `INSERT INTO workspace_documents (workspace_id, source, version, updated_at) VALUES ($1, 'MAIN', 4, now())`, ws); err != nil {
		t.Fatal(err)
	}
	png, _ := classify("fig/a.png", pngBytes)
	if _, err := store.Upload(ctx, ws, owner, []NewFile{png, text("b.tex", "B")}, false); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.Output(ctx, ws, owner); !errors.Is(err, ErrNoOutput) {
		t.Fatal(err)
	}
	if _, err := store.SaveOutput(ctx, ws, viewer, pdfBytes); err != nil {
		t.Fatalf("any member's compile is kept: %v", err)
	}
	title, pdf, err := store.Output(ctx, ws, owner)
	if err != nil || title != "Paper" || string(pdf) != string(pdfBytes) {
		t.Fatalf("%q %q %v", title, pdf, err)
	}
	b, err := store.Bundle(ctx, ws, viewer, true)
	if err != nil || b.Main != "MAIN" || b.Title != "Paper" || len(b.Files) != 3 || string(b.Files[0].Data) != "B" || string(b.Output) != string(pdfBytes) {
		t.Fatalf("%+v %v", b, err)
	}
	if b, _ := store.Bundle(ctx, ws, viewer, false); b.Output != nil {
		t.Fatal("no output unless asked")
	}
	l, err := store.List(ctx, ws, viewer)
	if err != nil || l.Main.Version != 4 || l.Main.Size != 4 || l.Output == nil || l.Output.Size != len(pdfBytes) || *l.Output.CompiledBy != viewer {
		t.Fatalf("%+v %v", l, err)
	}
	outsider := newUser(t, pool)
	if _, err := store.Bundle(ctx, ws, outsider, true); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.SaveOutput(ctx, ws, outsider, pdfBytes); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestPostgres_ImportCreatesEverythingOrNothing(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)

	png, _ := classify("fig/a.png", pngBytes)
	ws, err := store.Import(ctx, owner, "Thesis", `\documentclass{article}`, []NewFile{png, {Path: "empty", Kind: KindFolder}})
	if err != nil || ws.Role != "owner" || ws.Title != "Thesis" {
		t.Fatalf("%+v %v", ws, err)
	}
	l, err := store.List(ctx, ws.ID, owner)
	if err != nil || paths(l) != "empty,fig,fig/a.png" || l.Main.Version != 1 {
		t.Fatalf("%+v %v", l, err)
	}

	before := 0
	_ = pool.QueryRow(ctx, "SELECT count(*) FROM workspaces WHERE owner_id = $1", owner).Scan(&before)
	clash := []NewFile{text("a.tex", ""), {Path: "a.tex/b", Kind: KindFolder}}
	if _, err := store.Import(ctx, owner, "Broken", "x", clash); !errors.Is(err, ErrExists) {
		t.Fatalf("%v", err)
	}
	after := 0
	_ = pool.QueryRow(ctx, "SELECT count(*) FROM workspaces WHERE owner_id = $1", owner).Scan(&after)
	if after != before {
		t.Fatal("a failed import leaves no workspace behind")
	}
	if _, err := store.Import(ctx, "no-such-user", "X", "x", nil); !errors.Is(err, workspace.ErrProfileRequired) {
		t.Fatalf("%v", err)
	}
}

func TestPostgres_NilPoolIsUnavailable(t *testing.T) {
	store, ctx := NewPostgresStore(nil), context.Background()
	if _, err := store.List(ctx, "w", "u"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.Create(ctx, "w", "u", NewFile{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, _, err := store.Get(ctx, "w", "u", "f"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.Bundle(ctx, "w", "u", true); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.SaveOutput(ctx, "w", "u", nil); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, _, err := store.Output(ctx, "w", "u"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.Import(ctx, "u", "t", "m", nil); !errors.Is(err, workspace.ErrUnavailable) {
		t.Fatal(err)
	}
}
