// Package files keeps everything in a CoLab project beside its main.tex:
// folders, more .tex and .bib files, uploaded images and PDFs, and the PDF
// the last compile produced. main.tex itself stays in internal/document.
//
//	GET    /api/v1/workspaces/:id/files                 any member      -> 200 the project's listing
//	POST   /api/v1/workspaces/:id/files                 owner or editor -> 201 a new text file or folder
//	POST   /api/v1/workspaces/:id/files/upload          owner or editor -> 201 uploaded files (multipart)
//	GET    /api/v1/workspaces/:id/files/:file_id        any member      -> 200 a file, with a text file's content
//	GET    /api/v1/workspaces/:id/files/:file_id/raw    any member      -> 200 the file's bytes
//	PUT    /api/v1/workspaces/:id/files/:file_id        owner or editor -> 200 a text file saved (base_version)
//	PATCH  /api/v1/workspaces/:id/files/:file_id        owner or editor -> 200 renamed or moved
//	DELETE /api/v1/workspaces/:id/files/:file_id        owner or editor -> 204 (a folder with its contents)
//	GET    /api/v1/workspaces/:id/archive               any member      -> 200 the whole project as a .zip
//	POST   /api/v1/workspaces/:id/compile               any member      -> 200 compile result; a PDF is kept
//	GET    /api/v1/workspaces/:id/output.pdf            any member      -> 200 the kept PDF
//	POST   /api/v1/workspaces/import                    signed in       -> 201 a new workspace from a .zip
//
// Access follows the workspace rules (document.CallerRole): a workspace the
// caller cannot see is 404, never 403. Paths are relative, case-insensitively
// unique, and use the compile sandbox's rules (texsandbox.ValidPath), so
// every stored file can be written into a compile as it is.
package files

import (
	"bytes"
	"errors"
	"fmt"
	"path"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/skolab/backend-go/internal/texsandbox"
)

const (
	// MaxFileBytes is the largest single file (workspace_files.size CHECK).
	MaxFileBytes = 5 << 20
	// MaxTextBytes is the largest text file: a long .bib, not a book.
	MaxTextBytes = 1 << 20
	// MaxProjectBytes and MaxEntries bound a project beside main.tex, which
	// also bounds every compile request built from it.
	MaxProjectBytes = 10 << 20
	MaxEntries      = 200
	// MaxUploadBytes bounds an upload or import request body.
	MaxUploadBytes = 12 << 20

	MainPath   = "main.tex"
	OutputPath = "output.pdf"
)

const (
	KindFolder = "folder"
	KindText   = "text"
	KindBinary = "binary"
)

var (
	ErrNotFound      = errors.New("not found")
	ErrReadOnly      = errors.New("only the owner or an editor may change files")
	ErrExists        = errors.New("a file or folder already has that path")
	ErrFull          = errors.New("the project is full")
	ErrNotText       = errors.New("not a text file")
	ErrNotFile       = errors.New("a folder has no content")
	ErrInvalidMove   = errors.New("a folder cannot move into itself")
	ErrUnavailable   = errors.New("file store unavailable")
	ErrNoOutput      = errors.New("the project has not been compiled")
	ErrTypeChange    = errors.New("a renamed file keeps its type")
	errBadText       = errors.New("not valid text")
	errUnsupported   = errors.New("unsupported file type")
	errContentLooks  = errors.New("content does not match the file type")
	errReservedPath  = errors.New("reserved path")
	errInvalidPath   = errors.New("invalid path")
	errTooLargeEntry = errors.New("file too large")
)

// ConflictError reports that a text file moved on since base_version.
type ConflictError struct{ Current int }

func (e *ConflictError) Error() string { return fmt.Sprintf("file is at version %d", e.Current) }

// File is one entry of a project. Content is set for a text file when the
// caller asked for it.
type File struct {
	ID          string    `json:"id"`
	Path        string    `json:"path"`
	Kind        string    `json:"kind"`
	Size        int       `json:"size"`
	ContentType string    `json:"content_type"`
	Version     int       `json:"version"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
	UpdatedBy   *string   `json:"updated_by"`
	Content     *string   `json:"content,omitempty"`
}

// Main describes the project's main.tex (internal/document owns it).
type Main struct {
	Path      string     `json:"path"`
	Version   int        `json:"version"`
	Size      int        `json:"size"`
	UpdatedAt *time.Time `json:"updated_at"`
}

// Output describes the PDF the last successful compile produced.
type Output struct {
	Size       int       `json:"size"`
	CompiledAt time.Time `json:"compiled_at"`
	CompiledBy *string   `json:"compiled_by"`
	Status     string    `json:"status"`
}

type Usage struct {
	Bytes        int `json:"bytes"`
	LimitBytes   int `json:"limit_bytes"`
	Entries      int `json:"entries"`
	LimitEntries int `json:"limit_entries"`
}

// Listing is the whole project at a glance.
type Listing struct {
	WorkspaceID string  `json:"workspace_id"`
	Role        string  `json:"role"`
	Main        Main    `json:"main"`
	Files       []File  `json:"files"`
	Output      *Output `json:"output"`
	Usage       Usage   `json:"usage"`
}

// NewFile is a file or folder about to be stored.
type NewFile struct {
	Path        string
	Kind        string
	Text        string // KindText
	Data        []byte // KindBinary
	ContentType string
}

func (f NewFile) size() int {
	switch f.Kind {
	case KindText:
		return len(f.Text)
	case KindBinary:
		return len(f.Data)
	}
	return 0
}

// Stored is a file with everything a compile or an archive needs.
type Stored struct {
	Path string
	Kind string
	Data []byte
}

// Bundle is a whole project, for a compile or an archive.
type Bundle struct {
	Title  string
	Main   string
	Files  []Stored
	Output []byte
}

// textTypes and binaryTypes are the extensions a project may hold, with the
// content type each is served as.
var textTypes = map[string]string{
	"tex": "text/x-tex", "bib": "text/x-bibtex", "bst": "text/plain", "cls": "text/x-tex",
	"sty": "text/x-tex", "txt": "text/plain", "md": "text/markdown", "csv": "text/csv",
	"dat": "text/plain", "bbx": "text/plain", "cbx": "text/plain", "lbx": "text/plain",
	"def": "text/plain", "cfg": "text/plain", "clo": "text/plain", "ist": "text/plain",
	"tikz": "text/plain",
}

var binaryTypes = map[string]struct {
	contentType string
	magic       []byte
}{
	"png":  {"image/png", []byte("\x89PNG\r\n\x1a\n")},
	"jpg":  {"image/jpeg", []byte("\xff\xd8\xff")},
	"jpeg": {"image/jpeg", []byte("\xff\xd8\xff")},
	"pdf":  {"application/pdf", []byte("%PDF-")},
	"eps":  {"application/postscript", []byte("%!PS")},
}

// renamedType is the content type a file of this kind and content type has
// once renamed to target: a text file may take any text extension, an image
// or PDF only one of its own type, so stored bytes always match their name.
func renamedType(kind, contentType, target string) (string, bool) {
	ext := extension(target)
	switch kind {
	case KindFolder:
		return contentType, true
	case KindText:
		t, ok := textTypes[ext]
		return t, ok
	default:
		b, ok := binaryTypes[ext]
		return contentType, ok && b.contentType == contentType
	}
}

func extension(p string) string {
	return strings.ToLower(strings.TrimPrefix(path.Ext(p), "."))
}

// ValidPath reports whether p may name a project entry: the sandbox's path
// rules, and not one of the names the project itself owns at the root.
func ValidPath(p string) bool {
	return !reserved(p) && texsandbox.ValidPath(p)
}

// reserved reports whether p names main.tex or output.pdf at the root.
func reserved(p string) bool {
	lower := strings.ToLower(p)
	return lower == MainPath || lower == OutputPath
}

// pathError says why ValidPath refused p.
func pathError(p string) error {
	if reserved(p) {
		return errReservedPath
	}
	return errInvalidPath
}

// validText allows what a source file holds: printable text plus tab,
// newline and carriage return (Postgres text refuses NUL outright).
func validText(s string) bool {
	return utf8.ValidString(s) && strings.IndexFunc(s, func(r rune) bool {
		return unicode.IsControl(r) && r != '\t' && r != '\n' && r != '\r'
	}) < 0
}

// classify turns an uploaded file into a NewFile by its extension, checking
// that the bytes are what the extension claims.
func classify(p string, data []byte) (NewFile, error) {
	if !ValidPath(p) {
		return NewFile{}, pathError(p)
	}
	if len(data) > MaxFileBytes {
		return NewFile{}, errTooLargeEntry
	}
	ext := extension(p)
	if contentType, ok := textTypes[ext]; ok {
		if len(data) > MaxTextBytes {
			return NewFile{}, errTooLargeEntry
		}
		text := string(bytes.TrimPrefix(data, []byte("\xef\xbb\xbf"))) // a UTF-8 BOM is not content
		if !validText(text) {
			return NewFile{}, errBadText
		}
		return NewFile{Path: p, Kind: KindText, Text: text, ContentType: contentType}, nil
	}
	if binary, ok := binaryTypes[ext]; ok {
		if !bytes.HasPrefix(data, binary.magic) {
			return NewFile{}, errContentLooks
		}
		return NewFile{Path: p, Kind: KindBinary, Data: data, ContentType: binary.contentType}, nil
	}
	return NewFile{}, errUnsupported
}

// newText is a text file created in the editor (no upload, any extension
// listed in textTypes).
func newText(p, content string) (NewFile, error) {
	if !ValidPath(p) {
		return NewFile{}, pathError(p)
	}
	contentType, ok := textTypes[extension(p)]
	switch {
	case !ok:
		return NewFile{}, errUnsupported
	case len(content) > MaxTextBytes:
		return NewFile{}, errTooLargeEntry
	case !validText(content):
		return NewFile{}, errBadText
	}
	return NewFile{Path: p, Kind: KindText, Text: content, ContentType: contentType}, nil
}

// parents lists the folders above p, outermost first: "a/b/c.tex" -> a, a/b.
func parents(p string) []string {
	var out []string
	for i := strings.IndexByte(p, '/'); i >= 0; {
		out = append(out, p[:i])
		next := strings.IndexByte(p[i+1:], '/')
		if next < 0 {
			break
		}
		i += next + 1
	}
	return out
}

// within reports whether p is base itself or inside the folder base,
// ignoring case like the unique index does.
func within(p, base string) bool {
	p, base = strings.ToLower(p), strings.ToLower(base)
	return p == base || strings.HasPrefix(p, base+"/")
}
