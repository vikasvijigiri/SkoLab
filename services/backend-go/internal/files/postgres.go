package files

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/document"
	"github.com/skolab/backend-go/internal/workspace"
)

const (
	queryTimeout = 5 * time.Second
	// bulkTimeout covers moving up to ten megabytes in one statement batch:
	// an upload, an archive, a compile's inputs or an import.
	bulkTimeout = 20 * time.Second
)

type postgresStore struct {
	pool *pgxpool.Pool
}

// NewPostgresStore returns the production Store. A nil pool (database down
// at startup) makes every call fail with ErrUnavailable -> 503.
func NewPostgresStore(pool *pgxpool.Pool) Store {
	return &postgresStore{pool: pool}
}

const fileColumns = `id, path, kind, size, content_type, version, created_at, updated_at, updated_by`

func unavailable(err error) error { return errors.Join(ErrUnavailable, err) }

func scanFile(row pgx.Row, f *File) error {
	if err := row.Scan(&f.ID, &f.Path, &f.Kind, &f.Size, &f.ContentType, &f.Version, &f.CreatedAt, &f.UpdatedAt, &f.UpdatedBy); err != nil {
		return err
	}
	f.CreatedAt, f.UpdatedAt = f.CreatedAt.UTC(), f.UpdatedAt.UTC()
	return nil
}

func now() time.Time { return time.Now().UTC().Truncate(time.Microsecond) }

// role is the caller's role, mapping internal/document's errors to ours.
func role(ctx context.Context, q document.Querier, workspaceID, userID string) (string, error) {
	r, err := document.CallerRole(ctx, q, workspaceID, userID)
	switch {
	case errors.Is(err, document.ErrNotFound):
		return "", ErrNotFound
	case err != nil:
		return "", unavailable(err)
	}
	return r, nil
}

func canEdit(r string) bool { return r == "owner" || r == "editor" }

// writeTx opens a transaction for a change to workspaceID's files by
// userID, holding the workspace row so concurrent writers see each other's
// usage (the project limits) and paths.
func (s *postgresStore) writeTx(ctx context.Context, workspaceID, userID string) (pgx.Tx, error) {
	if s.pool == nil {
		return nil, ErrUnavailable
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, unavailable(err)
	}
	r, err := role(ctx, tx, workspaceID, userID)
	if err == nil && !canEdit(r) {
		err = ErrReadOnly
	}
	if err == nil {
		if _, lockErr := tx.Exec(ctx, `SELECT 1 FROM workspaces WHERE id = $1 FOR UPDATE`, workspaceID); lockErr != nil {
			err = unavailable(lockErr)
		}
	}
	if err != nil {
		_ = tx.Rollback(ctx)
		return nil, err
	}
	return tx, nil
}

func commit(ctx context.Context, tx pgx.Tx) error {
	if err := tx.Commit(ctx); err != nil {
		return unavailable(err)
	}
	return nil
}

// usage is the project's bytes and entries, leaving out the rows in except.
func usage(ctx context.Context, q document.Querier, workspaceID string, except []string) (Usage, error) {
	u := Usage{LimitBytes: MaxProjectBytes, LimitEntries: MaxEntries}
	if except == nil {
		except = []string{}
	}
	err := q.QueryRow(ctx, `SELECT COALESCE(sum(size), 0), count(*) FROM workspace_files WHERE workspace_id = $1 AND NOT (id = ANY($2))`,
		workspaceID, except).Scan(&u.Bytes, &u.Entries)
	if err != nil {
		return Usage{}, unavailable(err)
	}
	return u, nil
}

func uniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

func (s *postgresStore) List(ctx context.Context, workspaceID, userID string) (Listing, error) {
	if s.pool == nil {
		return Listing{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	r, err := role(ctx, s.pool, workspaceID, userID)
	if err != nil {
		return Listing{}, err
	}
	l := Listing{WorkspaceID: workspaceID, Role: r, Main: Main{Path: MainPath}, Files: []File{}}

	var updated time.Time
	err = s.pool.QueryRow(ctx, `SELECT version, octet_length(source), updated_at FROM workspace_documents WHERE workspace_id = $1`, workspaceID).
		Scan(&l.Main.Version, &l.Main.Size, &updated)
	switch {
	case err == nil:
		updated = updated.UTC()
		l.Main.UpdatedAt = &updated
	case !errors.Is(err, pgx.ErrNoRows):
		return Listing{}, unavailable(err)
	}

	rows, err := s.pool.Query(ctx, `SELECT `+fileColumns+` FROM workspace_files WHERE workspace_id = $1 ORDER BY lower(path)`, workspaceID)
	if err != nil {
		return Listing{}, unavailable(err)
	}
	for rows.Next() {
		var f File
		if err := scanFile(rows, &f); err != nil {
			rows.Close()
			return Listing{}, unavailable(err)
		}
		l.Files = append(l.Files, f)
		l.Usage.Bytes += f.Size
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return Listing{}, unavailable(err)
	}
	l.Usage.Entries, l.Usage.LimitBytes, l.Usage.LimitEntries = len(l.Files), MaxProjectBytes, MaxEntries

	var out Output
	err = s.pool.QueryRow(ctx, `SELECT size, compiled_at, compiled_by FROM workspace_outputs WHERE workspace_id = $1`, workspaceID).
		Scan(&out.Size, &out.CompiledAt, &out.CompiledBy)
	switch {
	case err == nil:
		out.CompiledAt, out.Status = out.CompiledAt.UTC(), "compiled"
		l.Output = &out
	case !errors.Is(err, pgx.ErrNoRows):
		return Listing{}, unavailable(err)
	}
	return l, nil
}

// ensureFolders creates the folders above p that do not exist yet, and
// refuses a path whose parent is a file. It returns how many it created.
func ensureFolders(ctx context.Context, tx pgx.Tx, workspaceID, userID, p string, at time.Time) (int, error) {
	created := 0
	for _, folder := range parents(p) {
		var kind string
		err := tx.QueryRow(ctx, `SELECT kind FROM workspace_files WHERE workspace_id = $1 AND lower(path) = lower($2)`, workspaceID, folder).Scan(&kind)
		switch {
		case err == nil && kind != KindFolder:
			return 0, ErrExists
		case err == nil:
			continue
		case !errors.Is(err, pgx.ErrNoRows):
			return 0, unavailable(err)
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO workspace_files (id, workspace_id, path, kind, size, content_type, version, created_at, updated_at, updated_by)
			VALUES ($1, $2, $3, 'folder', 0, '', 1, $4, $4, $5)`, uuid.NewString(), workspaceID, folder, at, userID); err != nil {
			if uniqueViolation(err) {
				return 0, ErrExists
			}
			return 0, unavailable(err)
		}
		created++
	}
	return created, nil
}

// insert stores f, its folders first. replaceID, when set, is the row f
// replaces (an upload over an existing file).
func insert(ctx context.Context, tx pgx.Tx, workspaceID, userID string, f NewFile, replaceID string, at time.Time) (File, error) {
	if _, err := ensureFolders(ctx, tx, workspaceID, userID, f.Path, at); err != nil {
		return File{}, err
	}
	var text *string
	var data []byte
	switch f.Kind {
	case KindText:
		text = &f.Text
	case KindBinary:
		data = f.Data
	}
	var out File
	var row pgx.Row
	if replaceID != "" {
		row = tx.QueryRow(ctx, `
			UPDATE workspace_files SET kind = $3, content = $4, data = $5, size = $6, content_type = $7,
			       version = version + 1, updated_at = $8, updated_by = $9
			WHERE workspace_id = $1 AND id = $2
			RETURNING `+fileColumns, workspaceID, replaceID, f.Kind, text, data, f.size(), f.ContentType, at, userID)
	} else {
		row = tx.QueryRow(ctx, `
			INSERT INTO workspace_files (id, workspace_id, path, kind, content, data, size, content_type, version, created_at, updated_at, updated_by)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 1, $9, $9, $10)
			RETURNING `+fileColumns, uuid.NewString(), workspaceID, f.Path, f.Kind, text, data, f.size(), f.ContentType, at, userID)
	}
	if err := scanFile(row, &out); err != nil {
		if uniqueViolation(err) {
			return File{}, ErrExists
		}
		return File{}, unavailable(err)
	}
	if f.Kind == KindText {
		out.Content = &f.Text
	}
	return out, nil
}

// fits checks the project limits after adding files (and the folders they
// need, at most a few per file) to u.
func fits(u Usage, files []NewFile) bool {
	bytes, entries := u.Bytes, u.Entries
	for _, f := range files {
		bytes += f.size()
		entries++
	}
	return bytes <= MaxProjectBytes && entries <= MaxEntries
}

func (s *postgresStore) Create(ctx context.Context, workspaceID, userID string, f NewFile) (File, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.writeTx(ctx, workspaceID, userID)
	if err != nil {
		return File{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	u, err := usage(ctx, tx, workspaceID, nil)
	if err != nil {
		return File{}, err
	}
	if !fits(u, []NewFile{f}) {
		return File{}, ErrFull
	}
	out, err := insert(ctx, tx, workspaceID, userID, f, "", now())
	if err != nil {
		return File{}, err
	}
	if err := checkLimits(ctx, tx, workspaceID); err != nil {
		return File{}, err
	}
	return out, commit(ctx, tx)
}

// checkLimits re-reads usage after a change (folders it created count too).
func checkLimits(ctx context.Context, tx pgx.Tx, workspaceID string) error {
	u, err := usage(ctx, tx, workspaceID, nil)
	if err != nil {
		return err
	}
	if u.Bytes > MaxProjectBytes || u.Entries > MaxEntries {
		return ErrFull
	}
	return nil
}

func (s *postgresStore) Upload(ctx context.Context, workspaceID, userID string, files []NewFile, replace bool) ([]File, error) {
	ctx, cancel := context.WithTimeout(ctx, bulkTimeout)
	defer cancel()
	tx, err := s.writeTx(ctx, workspaceID, userID)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	at := now()
	out := make([]File, 0, len(files))
	for _, f := range files {
		var id, kind string
		err := tx.QueryRow(ctx, `SELECT id, kind FROM workspace_files WHERE workspace_id = $1 AND lower(path) = lower($2)`, workspaceID, f.Path).Scan(&id, &kind)
		switch {
		case err == nil && (!replace || kind == KindFolder):
			return nil, ErrExists
		case err != nil && !errors.Is(err, pgx.ErrNoRows):
			return nil, unavailable(err)
		}
		stored, err := insert(ctx, tx, workspaceID, userID, f, id, at)
		if err != nil {
			return nil, err
		}
		stored.Content = nil // the upload answer lists files, not their text
		out = append(out, stored)
	}
	if err := checkLimits(ctx, tx, workspaceID); err != nil {
		return nil, err
	}
	return out, commit(ctx, tx)
}

func (s *postgresStore) Get(ctx context.Context, workspaceID, userID, fileID string) (File, []byte, error) {
	if s.pool == nil {
		return File{}, nil, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	if _, err := role(ctx, s.pool, workspaceID, userID); err != nil {
		return File{}, nil, err
	}
	var f File
	var text *string
	var data []byte
	row := s.pool.QueryRow(ctx, `SELECT `+fileColumns+`, content, data FROM workspace_files WHERE workspace_id = $1 AND id = $2`, workspaceID, fileID)
	err := row.Scan(&f.ID, &f.Path, &f.Kind, &f.Size, &f.ContentType, &f.Version, &f.CreatedAt, &f.UpdatedAt, &f.UpdatedBy, &text, &data)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return File{}, nil, ErrNotFound
	case err != nil:
		return File{}, nil, unavailable(err)
	}
	f.CreatedAt, f.UpdatedAt = f.CreatedAt.UTC(), f.UpdatedAt.UTC()
	if f.Kind == KindText && text != nil {
		f.Content = text
		data = []byte(*text)
	}
	return f, data, nil
}

func (s *postgresStore) SaveText(ctx context.Context, workspaceID, userID, fileID, content string, baseVersion int) (File, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.writeTx(ctx, workspaceID, userID)
	if err != nil {
		return File{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	var kind string
	var version int
	err = tx.QueryRow(ctx, `SELECT kind, version FROM workspace_files WHERE workspace_id = $1 AND id = $2`, workspaceID, fileID).Scan(&kind, &version)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return File{}, ErrNotFound
	case err != nil:
		return File{}, unavailable(err)
	case kind != KindText:
		return File{}, ErrNotText
	case version != baseVersion:
		return File{}, &ConflictError{Current: version}
	}
	u, err := usage(ctx, tx, workspaceID, []string{fileID})
	if err != nil {
		return File{}, err
	}
	if u.Bytes+len(content) > MaxProjectBytes {
		return File{}, ErrFull
	}
	var out File
	row := tx.QueryRow(ctx, `
		UPDATE workspace_files SET content = $3, size = $4, version = version + 1, updated_at = $5, updated_by = $6
		WHERE workspace_id = $1 AND id = $2
		RETURNING `+fileColumns, workspaceID, fileID, content, len(content), now(), userID)
	if err := scanFile(row, &out); err != nil {
		return File{}, unavailable(err)
	}
	out.Content = &content
	return out, commit(ctx, tx)
}

func (s *postgresStore) Move(ctx context.Context, workspaceID, userID, fileID, target string) (File, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.writeTx(ctx, workspaceID, userID)
	if err != nil {
		return File{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	var from, kind string
	err = tx.QueryRow(ctx, `SELECT path, kind FROM workspace_files WHERE workspace_id = $1 AND id = $2`, workspaceID, fileID).Scan(&from, &kind)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return File{}, ErrNotFound
	case err != nil:
		return File{}, unavailable(err)
	case kind == KindFolder && within(target, from) && !strings.EqualFold(target, from):
		return File{}, ErrInvalidMove
	}
	at := now()
	if _, err := ensureFolders(ctx, tx, workspaceID, userID, target, at); err != nil {
		return File{}, err
	}
	if kind == KindFolder {
		// Deepest children first is not needed: the unique index is checked
		// at the end of the statement, so one UPDATE renames the subtree.
		if _, err := tx.Exec(ctx, `
			UPDATE workspace_files SET path = $3 || substr(path, $4), updated_at = $5, updated_by = $6
			WHERE workspace_id = $1 AND id <> $2 AND left(lower(path), $4) = lower($7) || '/'`,
			workspaceID, fileID, target, utf8.RuneCountInString(from)+1, at, userID, from); err != nil {
			if uniqueViolation(err) {
				return File{}, ErrExists
			}
			return File{}, unavailable(err)
		}
	}
	var out File
	row := tx.QueryRow(ctx, `
		UPDATE workspace_files SET path = $3, updated_at = $4, updated_by = $5
		WHERE workspace_id = $1 AND id = $2
		RETURNING `+fileColumns, workspaceID, fileID, target, at, userID)
	if err := scanFile(row, &out); err != nil {
		if uniqueViolation(err) {
			return File{}, ErrExists
		}
		return File{}, unavailable(err)
	}
	if kind == KindFolder {
		// Children may now exceed the depth or length a path may have.
		var bad int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM workspace_files WHERE workspace_id = $1 AND left(lower(path), $2) = lower($3) || '/'
			AND (octet_length(path) > 200 OR length(path) - length(replace(path, '/', '')) > 5)`,
			workspaceID, utf8.RuneCountInString(target)+1, target).Scan(&bad); err != nil {
			return File{}, unavailable(err)
		}
		if bad > 0 {
			return File{}, ErrInvalidMove
		}
	}
	return out, commit(ctx, tx)
}

func (s *postgresStore) Delete(ctx context.Context, workspaceID, userID, fileID string) error {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.writeTx(ctx, workspaceID, userID)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	var p string
	err = tx.QueryRow(ctx, `DELETE FROM workspace_files WHERE workspace_id = $1 AND id = $2 RETURNING path`, workspaceID, fileID).Scan(&p)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return ErrNotFound
	case err != nil:
		return unavailable(err)
	}
	if _, err := tx.Exec(ctx, `DELETE FROM workspace_files WHERE workspace_id = $1 AND left(lower(path), $2) = lower($3) || '/'`,
		workspaceID, utf8.RuneCountInString(p)+1, p); err != nil {
		return unavailable(err)
	}
	return commit(ctx, tx)
}

func (s *postgresStore) Bundle(ctx context.Context, workspaceID, userID string, withOutput bool) (Bundle, error) {
	if s.pool == nil {
		return Bundle{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, bulkTimeout)
	defer cancel()
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly, IsoLevel: pgx.RepeatableRead})
	if err != nil {
		return Bundle{}, unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // read only

	if _, err := role(ctx, tx, workspaceID, userID); err != nil {
		return Bundle{}, err
	}
	var b Bundle
	if err := tx.QueryRow(ctx, `SELECT title FROM workspaces WHERE id = $1`, workspaceID).Scan(&b.Title); err != nil {
		return Bundle{}, unavailable(err)
	}
	err = tx.QueryRow(ctx, `SELECT source FROM workspace_documents WHERE workspace_id = $1`, workspaceID).Scan(&b.Main)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Bundle{}, unavailable(err)
	}
	rows, err := tx.Query(ctx, `SELECT path, kind, content, data FROM workspace_files WHERE workspace_id = $1 ORDER BY lower(path)`, workspaceID)
	if err != nil {
		return Bundle{}, unavailable(err)
	}
	for rows.Next() {
		var f Stored
		var text *string
		if err := rows.Scan(&f.Path, &f.Kind, &text, &f.Data); err != nil {
			rows.Close()
			return Bundle{}, unavailable(err)
		}
		if text != nil {
			f.Data = []byte(*text)
		}
		b.Files = append(b.Files, f)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return Bundle{}, unavailable(err)
	}
	if withOutput {
		err = tx.QueryRow(ctx, `SELECT pdf FROM workspace_outputs WHERE workspace_id = $1`, workspaceID).Scan(&b.Output)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return Bundle{}, unavailable(err)
		}
	}
	return b, nil
}

func (s *postgresStore) SaveOutput(ctx context.Context, workspaceID, userID string, pdf []byte) (Output, error) {
	if s.pool == nil {
		return Output{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, bulkTimeout)
	defer cancel()
	if _, err := role(ctx, s.pool, workspaceID, userID); err != nil {
		return Output{}, err
	}
	out := Output{Size: len(pdf), CompiledAt: now(), CompiledBy: &userID, Status: "compiled"}
	if _, err := s.pool.Exec(ctx, `
		INSERT INTO workspace_outputs (workspace_id, pdf, size, compiled_at, compiled_by) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (workspace_id) DO UPDATE SET pdf = EXCLUDED.pdf, size = EXCLUDED.size,
		       compiled_at = EXCLUDED.compiled_at, compiled_by = EXCLUDED.compiled_by`,
		workspaceID, pdf, len(pdf), out.CompiledAt, userID); err != nil {
		return Output{}, unavailable(err)
	}
	return out, nil
}

func (s *postgresStore) Output(ctx context.Context, workspaceID, userID string) (string, []byte, error) {
	if s.pool == nil {
		return "", nil, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, bulkTimeout)
	defer cancel()
	if _, err := role(ctx, s.pool, workspaceID, userID); err != nil {
		return "", nil, err
	}
	var title string
	var pdf []byte
	err := s.pool.QueryRow(ctx, `SELECT w.title, o.pdf FROM workspaces w LEFT JOIN workspace_outputs o ON o.workspace_id = w.id WHERE w.id = $1`, workspaceID).
		Scan(&title, &pdf)
	switch {
	case err != nil:
		return "", nil, unavailable(err)
	case pdf == nil:
		return title, nil, ErrNoOutput
	}
	return title, pdf, nil
}

func (s *postgresStore) Import(ctx context.Context, userID, title, main string, files []NewFile) (workspace.Workspace, error) {
	return workspace.CreateFilled(ctx, s.pool, userID, title, workspace.MaxPerUser(), bulkTimeout,
		func(ctx context.Context, tx pgx.Tx, ws workspace.Workspace) error {
			at := now()
			if _, err := tx.Exec(ctx, `
				INSERT INTO workspace_documents (workspace_id, source, version, updated_at, updated_by) VALUES ($1, $2, 1, $3, $4)`,
				ws.ID, main, at, userID); err != nil {
				return unavailable(err)
			}
			for _, f := range files {
				if _, err := insert(ctx, tx, ws.ID, userID, f, "", at); err != nil {
					return err
				}
			}
			return checkLimits(ctx, tx, ws.ID)
		})
}
