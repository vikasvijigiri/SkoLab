// Package firestore is a thin, nil-safe wrapper over the Firestore client that
// the Firebase Admin SDK already ships (firebase.google.com/go/v4 →
// app.Firestore(ctx)). It exists so Go-ported endpoints can keep the Firestore
// mirror tier that Python's pipeline (services/backend/app/services/platform/
// pipeline/base.py) and researcher_worker use — and degrade to a no-op exactly
// the way Python does when Firestore is unavailable.
package firestore

import (
	"context"
	"log"
	"sync"

	fs "cloud.google.com/go/firestore"
	firebase "firebase.google.com/go/v4"
	"google.golang.org/api/iterator"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

var (
	mu     sync.RWMutex
	client *fs.Client
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
		log.Printf("WARNING: Firestore init skipped — Firebase app unavailable (%v); Firestore tiers disabled\n", err)
		return
	}
	c, err := app.Firestore(context.Background())
	if err != nil {
		log.Printf("WARNING: Firestore client unavailable (%v); Firestore tiers disabled\n", err)
		return
	}
	mu.Lock()
	client = c
	mu.Unlock()
	log.Println("Firestore client initialized successfully.")
}

func get() *fs.Client {
	mu.RLock()
	defer mu.RUnlock()
	return client
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
			return nil, false, nil
		}
		return nil, false, err
	}
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
	return err
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
			return nil, err
		}
		out = append(out, snap.Data())
	}
	return out, nil
}
