// Package circuitbreaker implements a three-state circuit breaker (Closed → Open → HalfOpen).
// It wraps outbound calls to flaky external dependencies (e.g. OpenAlex API) and short-circuits
// after a configurable failure threshold, probing recovery after a reset timeout.
package circuitbreaker

import (
	"errors"
	"sync"
	"time"
)

// ErrOpen is returned when the breaker is open and calls are not allowed.
var ErrOpen = errors.New("circuit breaker is open")

type state int

const (
	stateClosed   state = iota // normal operation
	stateOpen                  // failing; reject all calls
	stateHalfOpen              // probing; allow one call to test recovery
)

// Breaker is a thread-safe circuit breaker.
type Breaker struct {
	mu               sync.Mutex
	state            state
	failures         int
	failureThreshold int
	resetTimeout     time.Duration
	openedAt         time.Time
}

// New returns a Breaker that opens after threshold consecutive failures and
// attempts recovery after resetTimeout.
func New(threshold int, resetTimeout time.Duration) *Breaker {
	return &Breaker{
		state:            stateClosed,
		failureThreshold: threshold,
		resetTimeout:     resetTimeout,
	}
}

// Allow returns nil if the call should proceed, or ErrOpen if it should be
// rejected. Callers must pair every successful Allow with either RecordSuccess
// or RecordFailure.
func (b *Breaker) Allow() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	switch b.state {
	case stateClosed:
		return nil

	case stateOpen:
		if time.Since(b.openedAt) >= b.resetTimeout {
			b.state = stateHalfOpen
			return nil
		}
		return ErrOpen

	case stateHalfOpen:
		// Only one probe at a time; treat subsequent requests as rejected.
		return ErrOpen
	}
	return nil
}

// RecordSuccess resets the breaker to closed.
func (b *Breaker) RecordSuccess() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.state = stateClosed
	b.failures = 0
}

// RecordFailure increments the failure counter and opens the breaker if the
// threshold is reached.
func (b *Breaker) RecordFailure() {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.failures++
	if b.state == stateHalfOpen || b.failures >= b.failureThreshold {
		b.state = stateOpen
		b.openedAt = time.Now()
		b.failures = 0
	}
}

// IsOpen reports whether the breaker is currently open.
func (b *Breaker) IsOpen() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.state == stateOpen
}
