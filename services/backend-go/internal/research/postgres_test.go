package research

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func researchTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeExec
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func TestPostgresResearchVersioningPermissionsAndQuality(t *testing.T) {
	pool := researchTestPool(t)
	ctx := context.Background()
	owner, editor, viewer := "test-"+uuid.NewString(), "test-"+uuid.NewString(), "test-"+uuid.NewString()
	for _, uid := range []string{owner, editor, viewer} {
		if _, err := pool.Exec(ctx, `INSERT INTO users(id,display_name) VALUES($1,'Research test')`, uid); err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() { _, _ = pool.Exec(ctx, `DELETE FROM users WHERE id=ANY($1)`, []string{owner, editor, viewer}) })
	ws := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO workspaces(id,owner_id,title,created_at) VALUES($1,$2,'Research test',now())`, ws, owner); err != nil {
		t.Fatal(err)
	}
	for uid, role := range map[string]string{editor: "editor", viewer: "viewer"} {
		if _, err := pool.Exec(ctx, `INSERT INTO workspace_members(workspace_id,user_id,role,status,created_at) VALUES($1,$2,$3,'active',now())`, ws, uid, role); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := pool.Exec(ctx, `INSERT INTO workspace_documents(workspace_id,source,version,updated_at) VALUES($1,$2,1,now())`, ws, `\begin{document}Claim \cite{known,missing}.\end{document}`); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO workspace_files(id,workspace_id,path,kind,content,size,content_type,version,created_at,updated_at) VALUES($1,$2,'refs.bib','text','@article{known, title={A}}',25,'text/x-bibtex',1,now(),now())`, uuid.NewString(), ws); err != nil {
		t.Fatal(err)
	}
	store := NewPostgresStore(pool)
	initial, err := store.Get(ctx, ws, viewer)
	if err != nil {
		t.Fatal(err)
	}
	if initial.Version != 0 || len(initial.Quality.UnresolvedKeys) != 1 || initial.Quality.UnresolvedKeys[0] != "missing" || len(initial.Quality.CitationsWithoutEvidence) != 2 {
		t.Fatalf("initial: %#v", initial)
	}
	item := Item{ID: "e1", Claim: "Claim", CitationKey: "known", Quote: "passage", Locator: "p. 2", Status: "supported_by_author"}
	saved, err := store.Save(ctx, ws, owner, Document{Items: []Item{item}, Reproducibility: Reproducibility{Software: "R 4.4"}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if saved.Version != 1 || len(saved.Quality.CitationsWithoutEvidence) != 1 || saved.Quality.CitationsWithoutEvidence[0] != "missing" {
		t.Fatalf("saved: %#v", saved)
	}
	if _, err = store.Save(ctx, ws, owner, Document{Items: []Item{item}}, 0); err == nil {
		t.Fatal("stale create did not conflict")
	} else {
		var conflict *ConflictError
		if !errors.As(err, &conflict) || conflict.Current != 1 {
			t.Fatalf("wrong conflict: %v", err)
		}
	}
	if _, err = store.Save(ctx, ws, viewer, Document{}, 1); !errors.Is(err, ErrReadOnly) {
		t.Fatalf("viewer write: %v", err)
	}
	updated, err := store.Save(ctx, ws, editor, Document{Items: []Item{item}, Reproducibility: Reproducibility{Software: "R 4.5"}}, 1)
	if err != nil || updated.Version != 2 {
		t.Fatalf("editor update: %#v %v", updated, err)
	}
	if _, err = store.Get(ctx, ws, "outsider"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("outsider read: %v", err)
	}
	if updated.UpdatedAt == nil || updated.UpdatedAt.After(time.Now().UTC().Add(time.Second)) {
		t.Fatalf("invalid updated_at: %v", updated.UpdatedAt)
	}
}
