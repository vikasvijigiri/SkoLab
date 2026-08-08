package circuitbreaker

import (
	"testing"
	"time"
)

func TestAllow_ClosedAlwaysAllows(t *testing.T) {
	b := New(3, time.Minute)
	for i := 0; i < 5; i++ {
		if err := b.Allow(); err != nil {
			t.Fatalf("call %d: expected nil error while closed, got %v", i, err)
		}
	}
}

func TestRecordFailure_OpensAtThreshold(t *testing.T) {
	b := New(3, time.Minute)

	b.RecordFailure()
	b.RecordFailure()
	if b.IsOpen() {
		t.Fatal("breaker opened before reaching failure threshold")
	}

	b.RecordFailure()
	if !b.IsOpen() {
		t.Fatal("expected breaker to open after 3 consecutive failures")
	}

	if err := b.Allow(); err != ErrOpen {
		t.Fatalf("expected ErrOpen once open, got %v", err)
	}
}

func TestOpen_RejectsUntilResetTimeout(t *testing.T) {
	b := New(1, 20*time.Millisecond)
	b.RecordFailure() // 1 failure hits threshold of 1 -> opens immediately

	if err := b.Allow(); err != ErrOpen {
		t.Fatalf("expected ErrOpen immediately after opening, got %v", err)
	}

	time.Sleep(30 * time.Millisecond)

	if err := b.Allow(); err != nil {
		t.Fatalf("expected nil (half-open probe allowed) after reset timeout, got %v", err)
	}
}

func TestHalfOpen_OnlyAllowsOneProbe(t *testing.T) {
	b := New(1, 10*time.Millisecond)
	b.RecordFailure()
	time.Sleep(20 * time.Millisecond)

	if err := b.Allow(); err != nil {
		t.Fatalf("first probe after timeout should be allowed, got %v", err)
	}
	// The breaker is now half-open (Allow() transitioned it) with the probe in flight.
	if err := b.Allow(); err != ErrOpen {
		t.Fatalf("second concurrent call while half-open should be rejected, got %v", err)
	}
}

func TestHalfOpen_FailureReopensImmediately(t *testing.T) {
	b := New(1, 10*time.Millisecond)
	b.RecordFailure()
	time.Sleep(20 * time.Millisecond)
	_ = b.Allow() // transitions to half-open, consumes the probe

	b.RecordFailure() // probe failed
	if !b.IsOpen() {
		t.Fatal("expected breaker to reopen immediately on a failed half-open probe")
	}
}

func TestRecordSuccess_ClosesAndResetsFailureCount(t *testing.T) {
	b := New(2, time.Minute)
	b.RecordFailure()
	b.RecordSuccess()

	if b.IsOpen() {
		t.Fatal("expected breaker to be closed after RecordSuccess")
	}

	// Failure count should have reset -- one more failure alone shouldn't open a
	// breaker with threshold 2.
	b.RecordFailure()
	if b.IsOpen() {
		t.Fatal("expected failure count to have reset after RecordSuccess")
	}
}
