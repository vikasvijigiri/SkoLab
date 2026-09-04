package firestore

import (
	"context"
	"testing"
)

// Init() is deliberately not exercised here: it needs a live Firebase Admin
// credential, which a unit test must not carry (same stance as
// internal/auth/firebase_test.go). What is covered is every path taken when the
// client is unavailable — the degraded state Init leaves the package in on any
// credential/network failure, and the contract the citation_heatmap handler
// relies on.

func withNilClient(t *testing.T) {
	t.Helper()
	mu.Lock()
	saved := client
	client = nil
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		client = saved
		mu.Unlock()
	})
}

func TestAvailableFalseWithoutClient(t *testing.T) {
	withNilClient(t)
	if Available() {
		t.Fatal("Available() = true with a nil client")
	}
}

func TestGetDocNoClientIsCleanMiss(t *testing.T) {
	withNilClient(t)
	data, found, err := GetDoc(context.Background(), "citation_heatmaps", "A123")
	if data != nil || found || err != nil {
		t.Fatalf("GetDoc(nil client) = (%v, %v, %v), want (nil, false, nil)", data, found, err)
	}
}

func TestSetDocNoClientIsNoOp(t *testing.T) {
	withNilClient(t)
	if err := SetDoc(context.Background(), "citation_heatmaps", "A123", map[string]any{"h_index": 7}); err != nil {
		t.Fatalf("SetDoc(nil client) = %v, want nil", err)
	}
}

func TestQueryEqNoClientIsCleanMiss(t *testing.T) {
	withNilClient(t)
	docs, err := QueryEq(context.Background(), "global_researchers", "display_name", "Ada Lovelace", 1)
	if docs != nil || err != nil {
		t.Fatalf("QueryEq(nil client) = (%v, %v), want (nil, nil)", docs, err)
	}
}

func TestServerTimestampIsUsableInADocMap(t *testing.T) {
	// Compile-time guarantee that callers can build the Firestore mirror payload
	// (as heatmap.go does) without importing the Firestore SDK directly.
	doc := map[string]any{"h_index": 7, "last_synced": ServerTimestamp}
	if _, ok := doc["last_synced"]; !ok {
		t.Fatal("ServerTimestamp did not land in the doc map")
	}
}
