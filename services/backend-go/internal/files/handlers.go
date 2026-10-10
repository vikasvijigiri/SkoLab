package files

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"math"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/security"
	"github.com/skolab/backend-go/internal/workspace"
)

// Store is narrow so handlers are tested without PostgreSQL.
type Store interface {
	List(ctx context.Context, workspaceID, userID string) (Listing, error)
	Create(ctx context.Context, workspaceID, userID string, f NewFile) (File, error)
	// Upload stores files in one transaction. With replace, a file at the
	// same path is overwritten (never a folder).
	Upload(ctx context.Context, workspaceID, userID string, files []NewFile, replace bool) ([]File, error)
	// Get returns the file and its bytes (a text file's UTF-8 text).
	Get(ctx context.Context, workspaceID, userID, fileID string) (File, []byte, error)
	SaveText(ctx context.Context, workspaceID, userID, fileID, content string, baseVersion int) (File, error)
	Move(ctx context.Context, workspaceID, userID, fileID, path string) (File, error)
	Delete(ctx context.Context, workspaceID, userID, fileID string) error
	Bundle(ctx context.Context, workspaceID, userID string, withOutput bool) (Bundle, error)
	SaveOutput(ctx context.Context, workspaceID, userID string, pdf []byte) (Output, error)
	// Output returns the workspace title and its kept PDF, or ErrNoOutput.
	Output(ctx context.Context, workspaceID, userID string) (string, []byte, error)
	Import(ctx context.Context, userID, title, main string, files []NewFile) (workspace.Workspace, error)
}

// Compiler runs a compile request through the gateway's compile pipeline
// (internal/colab): validation, the caller's single compile slot, the quota
// and the sandbox. On failure it has already answered c.
type Compiler interface {
	Run(c *gin.Context, uid string, body []byte) ([]byte, bool)
}

// Register mounts the routes on a group already protected by auth.VerifyUser().
func Register(group *gin.RouterGroup, store Store, compiler Compiler) {
	h := handlers{store: store, compiler: compiler}
	group.GET("/workspaces/:id/files", h.list)
	group.POST("/workspaces/:id/files", h.create)
	group.POST("/workspaces/:id/files/upload", h.upload)
	group.GET("/workspaces/:id/files/:file_id", h.get)
	group.GET("/workspaces/:id/files/:file_id/raw", h.raw)
	group.PUT("/workspaces/:id/files/:file_id", h.save)
	group.PATCH("/workspaces/:id/files/:file_id", h.move)
	group.DELETE("/workspaces/:id/files/:file_id", h.delete)
	group.GET("/workspaces/:id/archive", h.archive)
	group.POST("/workspaces/:id/compile", h.compile)
	group.GET("/workspaces/:id/output.pdf", h.output)
	group.POST("/workspaces/import", h.importZip)
}

// PostOnly answers the upload and import paths for any method but POST.
// "upload" and "import" are fixed segments where a file's :file_id and a
// workspace's :id also match, so without it a PATCH or PUT would reach a
// file or workspace handler, and Gin would list those handlers' methods in
// Allow. Register it globally, before CORS answers preflights.
func PostOnly() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !postOnlyPath(c.Request.URL.Path) {
			c.Next()
			return
		}
		if c.Request.Method == http.MethodPost {
			c.Next()
			return
		}
		c.Header("Allow", http.MethodPost)
		if c.Request.Method == http.MethodOptions {
			c.Next() // CORS answers the preflight
			return
		}
		apierror.NoMethod(c)
	}
}

func postOnlyPath(p string) bool {
	parts := strings.Split(strings.TrimSuffix(p, "/"), "/")
	switch {
	case len(parts) == 5: // "", api, v1, workspaces, import
		return parts[1] == "api" && parts[2] == "v1" && parts[3] == "workspaces" && parts[4] == "import"
	case len(parts) == 7: // "", api, v1, workspaces, :id, files, upload
		return parts[1] == "api" && parts[2] == "v1" && parts[3] == "workspaces" && parts[5] == "files" && parts[6] == "upload"
	}
	return false
}

// UploadRoutes are the routes whose bodies may exceed the gateway's default
// limit, up to MaxUploadBytes (see middleware.BodyLimit).
var UploadRoutes = []string{"/api/v1/workspaces/:id/files/upload", "/api/v1/workspaces/import"}

type handlers struct {
	store    Store
	compiler Compiler
}

func fail(c *gin.Context, status int, code, message string) {
	apierror.Abort(c, status, code, message)
}

func caller(c *gin.Context) (string, bool) {
	c.Header("Cache-Control", "no-store")
	uid := c.GetString("user_id")
	if uid == "" {
		fail(c, http.StatusUnauthorized, "unauthenticated", "Authentication is required")
		return "", false
	}
	return uid, true
}

func denied(c *gin.Context) {
	security.Record(c, security.Event{Name: security.WorkspaceDenied, Outcome: security.Denied, WorkspaceID: c.Param("id"), Reason: c.Request.Method})
}

// storeError maps a store error onto the API's stable error contract.
func storeError(c *gin.Context, err error) {
	var conflict *ConflictError
	switch {
	case errors.As(err, &conflict):
		c.Header("ETag", etag(conflict.Current))
		apierror.AbortWith(c, http.StatusConflict, "version_conflict",
			"The file was changed since you opened it. Reload it to see the latest version.",
			gin.H{"current_version": conflict.Current})
	case errors.Is(err, ErrNotFound):
		denied(c)
		fail(c, http.StatusNotFound, "not_found", "Not found, or you do not have access to it")
	case errors.Is(err, ErrReadOnly):
		denied(c)
		fail(c, http.StatusForbidden, "edit_forbidden", "Only the workspace owner or an editor may change files")
	case errors.Is(err, ErrExists):
		fail(c, http.StatusConflict, "file_exists", "A file or folder already has that name")
	case errors.Is(err, ErrFull):
		fail(c, http.StatusRequestEntityTooLarge, "project_full", "The project may hold at most 10 MB in 200 files and folders")
	case errors.Is(err, ErrNotText):
		fail(c, http.StatusBadRequest, "not_a_text_file", "Only text files can be edited")
	case errors.Is(err, ErrInvalidMove):
		fail(c, http.StatusBadRequest, "invalid_path", "A folder cannot move into itself, and paths must stay within the limits")
	case errors.Is(err, ErrTypeChange):
		fail(c, http.StatusUnsupportedMediaType, "unsupported_file_type", "A renamed file keeps its type: a text file needs a text extension, an image its own")
	case errors.Is(err, ErrNoOutput):
		fail(c, http.StatusNotFound, "no_output", "The project has not been compiled yet")
	case errors.Is(err, workspace.ErrLimitReached):
		fail(c, http.StatusForbidden, "workspace_limit_reached", "You have reached the maximum number of workspaces")
	case errors.Is(err, workspace.ErrProfileRequired):
		fail(c, http.StatusConflict, "profile_required", "Sync your profile first (POST /api/v1/users/profile/sync)")
	default:
		fail(c, http.StatusServiceUnavailable, "unavailable", "Project files are temporarily unavailable")
	}
}

// fileError answers a file the caller sent that cannot be stored.
func fileError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, errInvalidPath):
		fail(c, http.StatusBadRequest, "invalid_path", "Paths use letters, digits, spaces and . _ - , ( ) +, at most 5 folders deep, with no part starting with a dot")
	case errors.Is(err, errReservedPath):
		fail(c, http.StatusBadRequest, "reserved_path", "main.tex and output.pdf at the top of the project are managed for you")
	case errors.Is(err, errTooLargeEntry):
		fail(c, http.StatusRequestEntityTooLarge, "file_too_large", "A file may be at most 5 MB, and a text file at most 1 MB")
	case errors.Is(err, errBadText):
		fail(c, http.StatusBadRequest, "invalid_text", "Text files must be UTF-8 without control characters")
	case errors.Is(err, errContentLooks):
		fail(c, http.StatusUnsupportedMediaType, "unsupported_file_type", "The file's content does not match its extension")
	default:
		fail(c, http.StatusUnsupportedMediaType, "unsupported_file_type",
			"Supported files: .tex .bib .bst .cls .sty .txt .md .csv and other TeX text files, .png .jpg .jpeg .pdf .eps")
	}
}

func etag(version int) string { return `"v` + strconv.Itoa(version) + `"` }

// decode reads a JSON body into v, refusing unknown fields and trailing data.
func decode(c *gin.Context, v any) bool {
	decoder := json.NewDecoder(c.Request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(v); err != nil || decoder.More() {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			fail(c, http.StatusRequestEntityTooLarge, "body_too_large", "Request body is too large")
			return false
		}
		return false
	}
	return true
}

// integer reads a JSON number with no fractional part (JSON Schema's
// "integer"), like internal/document's base_version.
func integer(raw json.RawMessage) (int, bool) {
	if len(raw) == 0 || (raw[0] != '-' && (raw[0] < '0' || raw[0] > '9')) {
		return 0, false
	}
	f, err := strconv.ParseFloat(string(raw), 64)
	if err != nil || f != math.Trunc(f) || f < 0 || f > math.MaxInt32 {
		return 0, false
	}
	return int(f), true
}

func (h handlers) list(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	l, err := h.store.List(c.Request.Context(), c.Param("id"), uid)
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusOK, l)
}

type createRequest struct {
	Path    *string `json:"path"`
	Kind    string  `json:"kind"`
	Content *string `json:"content"`
}

func (h handlers) create(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req createRequest
	if !decode(c, &req) || c.IsAborted() {
		if !c.IsAborted() {
			fail(c, http.StatusBadRequest, "invalid_body", `Request body must be JSON with path and kind ("text" or "folder")`)
		}
		return
	}
	if req.Path == nil || (req.Kind != KindText && req.Kind != KindFolder) || (req.Kind == KindFolder && req.Content != nil) {
		fail(c, http.StatusBadRequest, "invalid_body", `Request body must be JSON with path and kind ("text" or "folder")`)
		return
	}
	var f NewFile
	if req.Kind == KindFolder {
		if !ValidPath(*req.Path) {
			fileError(c, pathError(*req.Path))
			return
		}
		f = NewFile{Path: *req.Path, Kind: KindFolder}
	} else {
		content := ""
		if req.Content != nil {
			content = *req.Content
		}
		var err error
		if f, err = newText(*req.Path, content); err != nil {
			fileError(c, err)
			return
		}
	}
	out, err := h.store.Create(c.Request.Context(), c.Param("id"), uid, f)
	if err != nil {
		storeError(c, err)
		return
	}
	c.Header("ETag", etag(out.Version))
	c.JSON(http.StatusCreated, out)
}

// readPart reads at most limit bytes of r, reporting whether it held more.
func readPart(r io.Reader, limit int64) ([]byte, bool, error) {
	data, err := io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		return nil, false, err
	}
	return data, int64(len(data)) > limit, nil
}

// readUpload reads a multipart upload: `file` parts, an optional `folder`
// and `replace`. Parts are streamed, so the body is never buffered whole
// beyond what the files themselves need.
func readUpload(c *gin.Context) (files []NewFile, replace bool, ok bool) {
	reader, err := c.Request.MultipartReader()
	if err != nil {
		fail(c, http.StatusBadRequest, "invalid_body", "Upload with multipart/form-data: one or more file parts")
		return nil, false, false
	}
	folder := ""
	type upload struct {
		name string
		data []byte
	}
	var uploads []upload
	for {
		part, err := reader.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			bodyError(c, err)
			return nil, false, false
		}
		switch part.FormName() {
		case "file":
			name := part.FileName()
			if name == "" || len(uploads) >= MaxEntries {
				fail(c, http.StatusBadRequest, "invalid_body", "Each file part needs a file name, at most 200 files at once")
				return nil, false, false
			}
			data, tooLarge, err := readPart(part, MaxFileBytes)
			if err != nil {
				bodyError(c, err)
				return nil, false, false
			}
			if tooLarge {
				fileError(c, errTooLargeEntry)
				return nil, false, false
			}
			uploads = append(uploads, upload{name: name, data: data})
		case "folder", "replace":
			value, tooLarge, err := readPart(part, 256)
			if err != nil {
				bodyError(c, err)
				return nil, false, false
			}
			if tooLarge {
				fail(c, http.StatusBadRequest, "invalid_body", "folder must be a project path")
				return nil, false, false
			}
			if part.FormName() == "folder" {
				folder = strings.Trim(string(value), "/")
			} else {
				replace = string(value) == "true"
			}
		default:
			fail(c, http.StatusBadRequest, "invalid_body", "Unexpected form field "+strconv.Quote(part.FormName()))
			return nil, false, false
		}
	}
	if len(uploads) == 0 {
		fail(c, http.StatusBadRequest, "invalid_body", "Upload with multipart/form-data: one or more file parts")
		return nil, false, false
	}
	if folder != "" && !ValidPath(folder) {
		fileError(c, pathError(folder))
		return nil, false, false
	}
	seen := map[string]bool{}
	for _, u := range uploads {
		// Browsers send a bare name; some tools send a path. Keep the name.
		name := u.name[strings.LastIndexAny(u.name, `/\`)+1:]
		p := name
		if folder != "" {
			p = folder + "/" + name
		}
		f, err := classify(p, u.data)
		if err != nil {
			fileError(c, err)
			return nil, false, false
		}
		if seen[strings.ToLower(p)] {
			fail(c, http.StatusConflict, "file_exists", "Two uploaded files have the same name")
			return nil, false, false
		}
		seen[strings.ToLower(p)] = true
		files = append(files, f)
	}
	return files, replace, true
}

func bodyError(c *gin.Context, err error) {
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) {
		fail(c, http.StatusRequestEntityTooLarge, "body_too_large", "An upload may be at most 12 MB")
		return
	}
	fail(c, http.StatusBadRequest, "invalid_body", "The upload could not be read")
}

func (h handlers) upload(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	files, replace, ok := readUpload(c)
	if !ok {
		return
	}
	out, err := h.store.Upload(c.Request.Context(), c.Param("id"), uid, files, replace)
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"files": out})
}

func (h handlers) get(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	f, _, err := h.store.Get(c.Request.Context(), c.Param("id"), uid, c.Param("file_id"))
	if err != nil {
		storeError(c, err)
		return
	}
	c.Header("ETag", etag(f.Version))
	c.JSON(http.StatusOK, f)
}

// inlineTypes are shown in the editor; everything else downloads.
var inlineTypes = map[string]bool{"image/png": true, "image/jpeg": true}

func (h handlers) raw(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	f, data, err := h.store.Get(c.Request.Context(), c.Param("id"), uid, c.Param("file_id"))
	if err != nil {
		storeError(c, err)
		return
	}
	if f.Kind == KindFolder {
		fail(c, http.StatusBadRequest, "not_a_file", "A folder has no content; download the project instead")
		return
	}
	contentType := f.ContentType
	if f.Kind == KindText {
		contentType += "; charset=utf-8"
	}
	disposition := "attachment"
	if inlineTypes[f.ContentType] {
		disposition = "inline"
	}
	name := f.Path[strings.LastIndexByte(f.Path, '/')+1:]
	c.Header("Content-Disposition", mime.FormatMediaType(disposition, map[string]string{"filename": name}))
	// Stored content is the uploader's: never let a browser run it.
	c.Header("Content-Security-Policy", "default-src 'none'; sandbox")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("ETag", etag(f.Version))
	c.Data(http.StatusOK, contentType, data)
}

type saveRequest struct {
	Content     *string         `json:"content"`
	BaseVersion json.RawMessage `json:"base_version"`
}

func (h handlers) save(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req saveRequest
	if !decode(c, &req) || c.IsAborted() {
		if !c.IsAborted() {
			fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with content and base_version")
		}
		return
	}
	version, integral := integer(req.BaseVersion)
	switch {
	case req.Content == nil || !integral || version < 1:
		fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with content and base_version (at least 1)")
		return
	case len(*req.Content) > MaxTextBytes:
		fileError(c, errTooLargeEntry)
		return
	case !validText(*req.Content):
		fileError(c, errBadText)
		return
	}
	out, err := h.store.SaveText(c.Request.Context(), c.Param("id"), uid, c.Param("file_id"), *req.Content, version)
	if err != nil {
		storeError(c, err)
		return
	}
	c.Header("ETag", etag(out.Version))
	c.JSON(http.StatusOK, out)
}

type moveRequest struct {
	Path *string `json:"path"`
}

func (h handlers) move(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req moveRequest
	if !decode(c, &req) || c.IsAborted() || req.Path == nil {
		if !c.IsAborted() {
			fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with the new path")
		}
		return
	}
	if !ValidPath(*req.Path) {
		fileError(c, pathError(*req.Path))
		return
	}
	out, err := h.store.Move(c.Request.Context(), c.Param("id"), uid, c.Param("file_id"), *req.Path)
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusOK, out)
}

func (h handlers) delete(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	if err := h.store.Delete(c.Request.Context(), c.Param("id"), uid, c.Param("file_id")); err != nil {
		storeError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func attachment(c *gin.Context, title, ext string) {
	c.Header("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": downloadName(title) + ext}))
	c.Header("X-Content-Type-Options", "nosniff")
}

func (h handlers) archive(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	b, err := h.store.Bundle(c.Request.Context(), c.Param("id"), uid, true)
	if err != nil {
		storeError(c, err)
		return
	}
	data, err := zipBundle(b, time.Now())
	if err != nil {
		fail(c, http.StatusServiceUnavailable, "unavailable", "Project files are temporarily unavailable")
		return
	}
	attachment(c, b.Title, ".zip")
	c.Data(http.StatusOK, "application/zip", data)
}

func (h handlers) output(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	title, pdf, err := h.store.Output(c.Request.Context(), c.Param("id"), uid)
	if err != nil {
		storeError(c, err)
		return
	}
	attachment(c, title, ".pdf")
	c.Data(http.StatusOK, "application/pdf", pdf)
}

type compileFile struct {
	Path          string `json:"path"`
	ContentBase64 string `json:"content_base64"`
}

type compileRequest struct {
	LatexSource string        `json:"latex_source"`
	Files       []compileFile `json:"files,omitempty"`
}

type compileResult struct {
	Status    string `json:"status"`
	PDFBase64 string `json:"pdf_base64"`
}

func (h handlers) compile(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	// The body is ignored: a compile always reads the saved project.
	b, err := h.store.Bundle(c.Request.Context(), c.Param("id"), uid, false)
	if err != nil {
		storeError(c, err)
		return
	}
	req := compileRequest{LatexSource: b.Main}
	for _, f := range b.Files {
		if f.Kind != KindFolder {
			req.Files = append(req.Files, compileFile{Path: f.Path, ContentBase64: base64.StdEncoding.EncodeToString(f.Data)})
		}
	}
	body, err := json.Marshal(req)
	if err != nil {
		fail(c, http.StatusServiceUnavailable, "unavailable", "Project files are temporarily unavailable")
		return
	}
	result, ok := h.compiler.Run(c, uid, body)
	if !ok {
		return
	}
	var parsed compileResult
	if json.Unmarshal(result, &parsed) == nil && parsed.Status == "compiled" {
		if pdf, err := base64.StdEncoding.DecodeString(parsed.PDFBase64); err == nil {
			// The compile itself succeeded; failing to keep the PDF only
			// costs the stored copy, so the caller still gets their result.
			if _, err := h.store.SaveOutput(context.WithoutCancel(c.Request.Context()), c.Param("id"), uid, pdf); err != nil {
				c.Header("X-Output-Stored", "false")
			}
		}
	}
	c.Data(http.StatusOK, "application/json", result)
}

func (h handlers) importZip(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	reader, err := c.Request.MultipartReader()
	if err != nil {
		fail(c, http.StatusBadRequest, "invalid_body", "Upload a .zip with multipart/form-data in a file part")
		return
	}
	var archive []byte
	var name, title string
	for {
		part, err := reader.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			bodyError(c, err)
			return
		}
		switch part.FormName() {
		case "file":
			data, tooLarge, err := readPart(part, MaxUploadBytes)
			if err != nil {
				bodyError(c, err)
				return
			}
			if tooLarge || archive != nil {
				fail(c, http.StatusBadRequest, "invalid_body", "Send one .zip of at most 12 MB")
				return
			}
			archive, name = data, part.FileName()
		case "title":
			value, tooLarge, err := readPart(part, 1024)
			if err != nil || tooLarge {
				fail(c, http.StatusBadRequest, "invalid_title", "Title must be at most 255 characters")
				return
			}
			title = string(value)
		default:
			fail(c, http.StatusBadRequest, "invalid_body", "Unexpected form field "+strconv.Quote(part.FormName()))
			return
		}
	}
	if archive == nil {
		fail(c, http.StatusBadRequest, "invalid_body", "Upload a .zip with multipart/form-data in a file part")
		return
	}
	project, err := unzipProject(archive)
	if err != nil {
		importError(c, err)
		return
	}
	title, ok = importTitle(title, name)
	if !ok {
		fail(c, http.StatusBadRequest, "invalid_title", "Title must be at most 255 printable characters")
		return
	}
	ws, err := h.store.Import(c.Request.Context(), uid, title, project.Main, project.Files)
	if err != nil {
		storeError(c, err)
		return
	}
	security.Audit(c, security.Event{Name: security.WorkspaceCreated, Outcome: security.Allowed, WorkspaceID: ws.ID})
	c.Header("Location", "/api/v1/workspaces/"+ws.ID)
	c.JSON(http.StatusCreated, gin.H{
		"id": ws.ID, "title": ws.Title, "owner_id": ws.OwnerID, "role": ws.Role, "created_at": ws.CreatedAt,
		"skipped": project.Skipped,
	})
}
