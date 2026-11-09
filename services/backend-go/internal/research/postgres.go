package research

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/skolab/backend-go/internal/document"
)

type postgresStore struct{ pool *pgxpool.Pool }

func NewPostgresStore(pool *pgxpool.Pool) Store { return &postgresStore{pool: pool} }

func (s *postgresStore) Get(ctx context.Context, workspaceID, userID string) (Document, error) {
	if s.pool == nil {
		return Document{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly, IsoLevel: pgx.RepeatableRead})
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	defer tx.Rollback(ctx)
	role, err := document.CallerRole(ctx, tx, workspaceID, userID)
	if errors.Is(err, document.ErrNotFound) {
		return Document{}, ErrNotFound
	}
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	doc := Document{WorkspaceID: workspaceID, Items: []Item{}, Role: role, Reproducibility: Reproducibility{}}
	var source string
	if err = tx.QueryRow(ctx, `SELECT source FROM workspace_documents WHERE workspace_id=$1`, workspaceID).Scan(&source); err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	var rawItems, rawRepro []byte
	err = tx.QueryRow(ctx, `SELECT items, reproducibility, version, updated_at FROM workspace_research WHERE workspace_id=$1`, workspaceID).Scan(&rawItems, &rawRepro, &doc.Version, &doc.UpdatedAt)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	if err == nil {
		if err = json.Unmarshal(rawItems, &doc.Items); err != nil {
			return Document{}, errors.Join(ErrUnavailable, err)
		}
		if err = json.Unmarshal(rawRepro, &doc.Reproducibility); err != nil {
			return Document{}, errors.Join(ErrUnavailable, err)
		}
	}
	if doc.Items == nil {
		doc.Items = []Item{}
	}
	if doc.UpdatedAt != nil {
		t := doc.UpdatedAt.UTC()
		doc.UpdatedAt = &t
	}
	rows, err := tx.Query(ctx, `SELECT content FROM workspace_files WHERE workspace_id=$1 AND kind='text' AND lower(path) LIKE '%.bib'`, workspaceID)
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	var bibliography []string
	for rows.Next() {
		var content *string
		if err = rows.Scan(&content); err != nil {
			rows.Close()
			return Document{}, errors.Join(ErrUnavailable, err)
		}
		if content != nil {
			bibliography = append(bibliography, *content)
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	rows.Close()
	doc.Quality = AnalyzeQuality(source, bibliography, doc.Items)
	if err = tx.Commit(ctx); err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	return doc, nil
}

func (s *postgresStore) Save(ctx context.Context, workspaceID, userID string, value Document, base int) (Document, error) {
	if s.pool == nil {
		return Document{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	defer tx.Rollback(ctx)
	role, err := document.CallerRole(ctx, tx, workspaceID, userID)
	if errors.Is(err, document.ErrNotFound) {
		return Document{}, ErrNotFound
	}
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	if role != "owner" && role != "editor" {
		return Document{}, ErrReadOnly
	}
	if _, err = tx.Exec(ctx, `SELECT 1 FROM workspaces WHERE id=$1 FOR UPDATE`, workspaceID); err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	// Recheck after locking the workspace so a concurrent member removal or
	// role downgrade cannot race the write authorization check above.
	role, err = document.CallerRole(ctx, tx, workspaceID, userID)
	if errors.Is(err, document.ErrNotFound) {
		return Document{}, ErrNotFound
	}
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	if role != "owner" && role != "editor" {
		return Document{}, ErrReadOnly
	}
	items, err := json.Marshal(value.Items)
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	repro, err := json.Marshal(value.Reproducibility)
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	now := time.Now().UTC().Truncate(time.Microsecond)
	var doc Document
	doc.WorkspaceID = workspaceID
	doc.Role = role
	doc.Items = value.Items
	doc.Reproducibility = value.Reproducibility
	var rawItems, rawRepro []byte
	var row pgx.Row
	if base == 0 {
		row = tx.QueryRow(ctx, `INSERT INTO workspace_research(workspace_id,items,reproducibility,version,updated_at,updated_by) VALUES($1,$2,$3,1,$4,$5) ON CONFLICT(workspace_id) DO NOTHING RETURNING items,reproducibility,version,updated_at`, workspaceID, items, repro, now, userID)
	} else {
		row = tx.QueryRow(ctx, `UPDATE workspace_research SET items=$2,reproducibility=$3,version=version+1,updated_at=$4,updated_by=$5 WHERE workspace_id=$1 AND version=$6 RETURNING items,reproducibility,version,updated_at`, workspaceID, items, repro, now, userID, base)
	}
	err = row.Scan(&rawItems, &rawRepro, &doc.Version, &doc.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		var current int
		err = tx.QueryRow(ctx, `SELECT version FROM workspace_research WHERE workspace_id=$1`, workspaceID).Scan(&current)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return Document{}, errors.Join(ErrUnavailable, err)
		}
		return Document{}, &ConflictError{Current: current}
	}
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	var source string
	err = tx.QueryRow(ctx, `SELECT source FROM workspace_documents WHERE workspace_id=$1`, workspaceID).Scan(&source)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	rows, err := tx.Query(ctx, `SELECT content FROM workspace_files WHERE workspace_id=$1 AND kind='text' AND lower(path) LIKE '%.bib'`, workspaceID)
	if err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	var bibliography []string
	for rows.Next() {
		var content *string
		if err = rows.Scan(&content); err != nil {
			rows.Close()
			return Document{}, errors.Join(ErrUnavailable, err)
		}
		if content != nil {
			bibliography = append(bibliography, *content)
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	rows.Close()
	doc.Quality = AnalyzeQuality(source, bibliography, doc.Items)
	if err = tx.Commit(ctx); err != nil {
		return Document{}, errors.Join(ErrUnavailable, err)
	}
	doc.UpdatedAt = &now
	return doc, nil
}
