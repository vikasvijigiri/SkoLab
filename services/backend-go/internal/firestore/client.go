// Package firestore is a thin, nil-safe wrapper over the Firestore client that
// the Firebase Admin SDK already ships (firebase.google.com/go/v4 →
// app.Firestore(ctx)). It exists so Go-ported endpoints can keep the Firestore
// mirror tier that Python's pipeline (services/backend/app/services/platform/
// pipeline/base.py) and researcher_worker use — and degrade to a no-op exactly
// the way Python does when Firestore is unavailable.
package firestore

import (
	"context"
	"log/slog"
	"sync"
	"time"

	fs "cloud.google.com/go/firestore"
	firebase "firebase.google.com/go/v4"
	"google.golang.org/api/iterator"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/skolab/backend-go/internal/circuitbreaker"
)

var (
	mu     sync.RWMutex
	client *fs.Client
	// breaker mirrors services/backend/app/core/circuit_breaker.py's
	// firestore_breaker (2026-09-26 reliability audit): this package
	// previously had no runtime failure tracking at all, only "is a client
	// wired" — a mid-session outage meant every call kept trying and timing
	// out individually instead of backing off, same gap as the Python side.
	breaker = circuitbreaker.New(5, 30*time.Second)
)

// ServerTimestamp is re-exported so callers can request a server-set timestamp
// field without importing the Firestore SDK directly. Mirrors Python's
// firestore.SERVER_TIMESTAMP usage in heatmap.py.
var ServerTimestamp = fs.ServerTimestamp

// Init wires a Firestore client from a Firebase Admin app.
//
// It builds its own firebase.App via firebase.NewApp rather than sharing the one
// in internal/auth: that variable is package-local there and the auth file is
// under concurrent edit, so exporting it would only add a merge conflict. This
// uses the *same* Firebase SDK and the *same* ambient Application Default
// Credentials — no separate GCP SDK, no separate creds path. Any failure
// (missing creds, no network) is logged at WARNING and leaves the client nil;
// every method below then degrades to a no-op. Never fatal.
func Init() {
	app, err := firebase.NewApp(context.Background(), nil)
	if err != nil {
		slog.Warn("Firestore init skipped — Firebase app unavailable; Firestore tiers disabled", "err", err)
		return
	}
	c, err := app.Firestore(context.Background())
	if err != nil {
		slog.Warn("Firestore client unavailable; Firestore tiers disabled", "err", err)
		return
	}
	mu.Lock()
	client = c
	mu.Unlock()
	slog.Info("Firestore client initialized successfully.")
}

// get returns the wired client, or nil if either no client is configured or
// the breaker is currently open — both cases degrade identically at every
// call site below.
func get() *fs.Client {
	mu.RLock()
	c := client
	mu.RUnlock()
	if c == nil {
		return nil
	}
	if err := breaker.Allow(); err != nil {
		return nil
	}
	return c
}

// Available reports whether a Firestore client is wired.
func Available() bool { return get() != nil }

// GetDoc returns the document's data map.
//   - client unavailable  → (nil, false, nil)   — caller treats as "no mirror"
//   - document not found   → (nil, false, nil)
//   - other error          → (nil, false, err)
func GetDoc(ctx context.Context, collection, docID string) (map[string]any, bool, error) {
	c := get()
	if c == nil {
		return nil, false, nil
	}
	snap, err := c.Collection(collection).Doc(docID).Get(ctx)
	if err != nil {
		if status.Code(err) == codes.NotFound {
			// A miss is a normal outcome, not a dependency failure.
			breaker.RecordSuccess()
			return nil, false, nil
		}
		breaker.RecordFailure()
		return nil, false, err
	}
	breaker.RecordSuccess()
	return snap.Data(), true, nil
}

// SetDoc writes (overwrites) the document. Client unavailable → nil no-op,
// matching Python's _firestore_set_safe returning False without raising.
func SetDoc(ctx context.Context, collection, docID string, data map[string]any) error {
	c := get()
	if c == nil {
		return nil
	}
	_, err := c.Collection(collection).Doc(docID).Set(ctx, data)
	if err != nil {
		breaker.RecordFailure()
		return err
	}
	breaker.RecordSuccess()
	return nil
}

// ListDocs returns every document in collection, capped at limit — for
// collections with no useful equality filter to query by (e.g. a per-user
// subcollection like `users/{uid}/tracked_researchers`, where the caller just
// wants "all of them").
//   - client unavailable → (nil, nil) — same no-op degradation as GetDoc/QueryEq
//   - empty collection    → ([]map[string]any{}, nil)
//   - other error         → (nil, err)
func ListDocs(ctx context.Context, collection string, limit int) ([]map[string]any, error) {
	c := get()
	if c == nil {
		return nil, nil
	}
	iter := c.Collection(collection).Limit(limit).Documents(ctx)
	defer iter.Stop()

	out := make([]map[string]any, 0, limit)
	for {
		snap, err := iter.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			breaker.RecordFailure()
			return nil, err
		}
		out = append(out, snap.Data())
	}
	breaker.RecordSuccess()
	return out, nil
}

// Doc pairs a document's Firestore-assigned id with its data — for callers
// that need the id back afterward (e.g. deleting a doc once it's been read,
// like the activity feed's inbox drain).
type Doc struct {
	ID   string
	Data map[string]any
}

// ListDocsWithIDs is ListDocs but also returns each document's id.
//   - client unavailable → (nil, nil) — same no-op degradation as ListDocs
//   - empty collection    → ([]Doc{}, nil)
//   - other error         → (nil, err)
func ListDocsWithIDs(ctx context.Context, collection string, limit int) ([]Doc, error) {
	c := get()
	if c == nil {
		return nil, nil
	}
	iter := c.Collection(collection).Limit(limit).Documents(ctx)
	defer iter.Stop()

	out := make([]Doc, 0, limit)
	for {
		snap, err := iter.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			breaker.RecordFailure()
			return nil, err
		}
		out = append(out, Doc{ID: snap.Ref.ID, Data: snap.Data()})
	}
	breaker.RecordSuccess()
	return out, nil
}

// DeleteDoc removes a document. Client unavailable → nil no-op, matching
// every other write in this package's degrade-safe contract.
func DeleteDoc(ctx context.Context, collection, docID string) error {
	c := get()
	if c == nil {
		return nil
	}
	_, err := c.Collection(collection).Doc(docID).Delete(ctx)
	if err != nil {
		breaker.RecordFailure()
		return err
	}
	breaker.RecordSuccess()
	return nil
}

// QueryEq runs an equality query (`field == value`) against collection,
// capped at limit results, and returns each matching document's data map.
//   - client unavailable → (nil, nil) — same no-op degradation as GetDoc
//   - no matches          → ([]map[string]any{}, nil)
//   - other error         → (nil, err)
func QueryEq(ctx context.Context, collection, field string, value any, limit int) ([]map[string]any, error) {
	c := get()
	if c == nil {
		return nil, nil
	}
	iter := c.Collection(collection).Where(field, "==", value).Limit(limit).Documents(ctx)
	defer iter.Stop()

	out := make([]map[string]any, 0, limit)
	for {
		snap, err := iter.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			breaker.RecordFailure()
			return nil, err
		}
		out = append(out, snap.Data())
	}
	breaker.RecordSuccess()
	return out, nil
}
