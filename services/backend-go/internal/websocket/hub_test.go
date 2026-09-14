package websocket

import (
	"testing"
	"time"
)

// 2026-09-14 endpoint audit: the Hub used to keep one flat set of clients and
// broadcast every message to all of them, ignoring :workspace_id entirely --
// self-documented in this package's own prior comments as a known gap. These
// tests lock in the fix: Clients is now partitioned by workspace_id, and a
// broadcast only ever reaches other clients in the same workspace as the
// sender.

// newTestClient builds a Client with just enough state for Hub.Run() to
// register/unregister/broadcast it -- no real websocket connection needed
// since these tests never touch conn.
func newTestClient(workspaceID string) *Client {
	return &Client{workspaceID: workspaceID, send: make(chan []byte, 4)}
}

func TestHub_BroadcastOnlyReachesSameWorkspace(t *testing.T) {
	h := NewHub()
	go h.Run()

	sameWorkspace := newTestClient("workspace-a")
	alsoSameWorkspace := newTestClient("workspace-a")
	differentWorkspace := newTestClient("workspace-b")

	h.Register <- sameWorkspace
	h.Register <- alsoSameWorkspace
	h.Register <- differentWorkspace

	h.Publish("workspace-a", []byte("hello-a"))

	for name, c := range map[string]*Client{
		"sameWorkspace":     sameWorkspace,
		"alsoSameWorkspace": alsoSameWorkspace,
	} {
		select {
		case msg := <-c.send:
			if string(msg) != "hello-a" {
				t.Fatalf("%s got %q, want %q", name, msg, "hello-a")
			}
		case <-time.After(time.Second):
			t.Fatalf("%s (same workspace as the sender) never received the broadcast", name)
		}
	}

	select {
	case msg, ok := <-differentWorkspace.send:
		if ok {
			t.Fatalf("client in a DIFFERENT workspace received a message meant for workspace-a: %q", msg)
		}
	case <-time.After(200 * time.Millisecond):
		// No message crossed the workspace boundary within a generous
		// window -- expected.
	}
}

func TestHub_UnregisterRemovesOnlyThatClientAndClosesItsChannel(t *testing.T) {
	h := NewHub()
	go h.Run()

	leaving := newTestClient("workspace-a")
	staying := newTestClient("workspace-a")
	h.Register <- leaving
	h.Register <- staying

	h.Unregister <- leaving

	// Give Run() a moment to process the unregister before publishing --
	// otherwise the publish could race ahead of it.
	time.Sleep(20 * time.Millisecond)

	h.Publish("workspace-a", []byte("still-here"))

	select {
	case _, ok := <-leaving.send:
		if ok {
			t.Fatal("unregistered client received a broadcast meant for the workspace it left")
		}
		// ok == false: channel closed by Unregister, as expected.
	case <-time.After(time.Second):
		t.Fatal("expected the unregistered client's send channel to be closed")
	}

	select {
	case msg := <-staying.send:
		if string(msg) != "still-here" {
			t.Fatalf("remaining client got %q, want %q", msg, "still-here")
		}
	case <-time.After(time.Second):
		t.Fatal("the client that stayed in the workspace should still receive broadcasts")
	}
}

func TestHub_DifferentWorkspacesDoNotInterfereOnRegister(t *testing.T) {
	h := NewHub()
	go h.Run()

	a := newTestClient("workspace-a")
	b := newTestClient("workspace-b")
	h.Register <- a
	h.Register <- b

	h.Publish("workspace-b", []byte("only-for-b"))

	select {
	case msg := <-b.send:
		if string(msg) != "only-for-b" {
			t.Fatalf("b got %q, want %q", msg, "only-for-b")
		}
	case <-time.After(time.Second):
		t.Fatal("b never received a broadcast addressed to its own workspace")
	}

	select {
	case msg, ok := <-a.send:
		if ok {
			t.Fatalf("a received a broadcast addressed to a different workspace: %q", msg)
		}
	case <-time.After(200 * time.Millisecond):
		// expected: a's channel is untouched (still open, no message).
	}
}
