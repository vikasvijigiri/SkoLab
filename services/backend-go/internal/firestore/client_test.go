package firestore

import (
	"context"
	"testing"

	fs "cloud.google.com/go/firestore"
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

func TestListDocsNoClientIsCleanMiss(t *testing.T) {
	withNilClient(t)
	docs, err := ListDocs(context.Background(), "users/u1/tracked_researchers", 25)
	if docs != nil || err != nil {
		t.Fatalf("ListDocs(nil client) = (%v, %v), want (nil, nil)", docs, err)
	}
}

func TestListDocsWithIDsNoClientIsCleanMiss(t *testing.T) {
	withNilClient(t)
	docs, err := ListDocsWithIDs(context.Background(), "users/u1/inbox", 40)
	if docs != nil || err != nil {
		t.Fatalf("ListDocsWithIDs(nil client) = (%v, %v), want (nil, nil)", docs, err)
	}
}

func TestDeleteDocNoClientIsNoOp(t *testing.T) {
	withNilClient(t)
	if err := DeleteDoc(context.Background(), "users/u1/inbox", "item1"); err != nil {
		t.Fatalf("DeleteDoc(nil client) = %v, want nil", err)
	}
}

// withOpenBreaker forces the package breaker open regardless of a wired
// client, and restores it afterward — same shape as withNilClient.
func withOpenBreaker(t *testing.T) {
	t.Helper()
	for range 5 {
		breaker.RecordFailure()
	}
	t.Cleanup(func() { breaker.RecordSuccess() })
}

func TestGetDocOpenBreakerIsCleanMissEvenWithAClient(t *testing.T) {
	// A non-nil client with the breaker open must degrade exactly like a nil
	// client — this is the gap the 2026-09-26 reliability audit found: a
	// mid-session Firestore outage previously kept retrying every call
	// individually instead of backing off (get() only checked "is a client
	// wired", never "did the last few calls actually succeed").
	mu.Lock()
	client = &fs.Client{}
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		client = nil
		mu.Unlock()
	})
	withOpenBreaker(t)

	data, found, err := GetDoc(context.Background(), "citation_heatmaps", "A123")
	if data != nil || found || err != nil {
		t.Fatalf("GetDoc(open breaker) = (%v, %v, %v), want (nil, false, nil)", data, found, err)
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
