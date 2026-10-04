package auth

import (
	"context"
	"errors"
	"sync"
	"time"

	"firebase.google.com/go/v4/auth"
)

// identityProvider is the subset of *auth.Client the gateway uses, so tests
// can substitute a fake without a Firebase project.
type identityProvider interface {
	VerifyIDToken(ctx context.Context, idToken string) (*auth.Token, error)
	GetUser(ctx context.Context, uid string) (*auth.UserRecord, error)
	RevokeRefreshTokens(ctx context.Context, uid string) error
	DeleteUser(ctx context.Context, uid string) error
}

var (
	errRevoked             = errors.New("session revoked, account disabled or account deleted")
	errStatusUnavailable   = errors.New("account status unavailable")
	userStatusTTL          = 60 * time.Second // revocation takes effect within this window
	userStatusMaxStale     = 15 * time.Minute // serve a known user through a Firebase blip
	userStatusMaxEntries   = 100_000
	userStatusFetchTimeout = 5 * time.Second
)

// isUserNotFound is a variable because Firebase's error type cannot be
// constructed outside its SDK; tests substitute it.
var isUserNotFound = auth.IsUserNotFound

type userStatus struct {
	validAfterMillis int64 // tokens authenticated before this are revoked
	disabled         bool
	fetched          time.Time
}

// revocationCache replaces Firebase's check-revoked-on-every-request (one
// network round trip per API call, and every login down whenever Firebase
// is) with the same comparison against a per-user status cached briefly.
type revocationCache struct {
	mu      sync.Mutex
	entries map[string]userStatus
	now     func() time.Time
}

var revocations = &revocationCache{entries: map[string]userStatus{}, now: time.Now}

// CheckSession also applies to established sockets, using the original
// Firebase sign-in time preserved in their one-use connection ticket.
func CheckSession(ctx context.Context, uid string, authTime int64) error {
	if authClient == nil || authTime <= 0 {
		return errStatusUnavailable
	}
	return revocations.check(ctx, authClient, uid, authTime)
}

// check fails with errRevoked when the token's sign-in predates the user's
// revocation, or the account is disabled or deleted; with
// errStatusUnavailable when Firebase cannot be reached and no recent status
// is known (fail closed).
func (c *revocationCache) check(ctx context.Context, provider identityProvider, uid string, authTime int64) error {
	c.mu.Lock()
	entry, known := c.entries[uid]
	c.mu.Unlock()
	now := c.now()

	if !known || now.Sub(entry.fetched) >= userStatusTTL {
		fetchCtx, cancel := context.WithTimeout(ctx, userStatusFetchTimeout)
		user, err := provider.GetUser(fetchCtx, uid)
		cancel()
		switch {
		case err == nil:
			entry = userStatus{validAfterMillis: user.TokensValidAfterMillis, disabled: user.Disabled, fetched: now}
			c.store(uid, entry)
		case isUserNotFound(err):
			c.forget(uid)
			return errRevoked
		case known && now.Sub(entry.fetched) < userStatusMaxStale:
			// Firebase is unreachable: a recently verified user keeps working.
		default:
			return errors.Join(errStatusUnavailable, err)
		}
	}
	if entry.disabled || authTime*1000 < entry.validAfterMillis {
		return errRevoked
	}
	return nil
}

func (c *revocationCache) store(uid string, entry userStatus) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) >= userStatusMaxEntries {
		c.entries = map[string]userStatus{} // bounded memory; entries refill on demand
	}
	c.entries[uid] = entry
}

func (c *revocationCache) forget(uid string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.entries, uid)
}

// DeleteIdentity ends every session of uid and deletes its Firebase account,
// so a deleted SkoLab user cannot keep using a token or sign back in. It is
// idempotent (an already-deleted account is not an error) and a no-op when
// Firebase is not configured (development; release refuses requests then).
func DeleteIdentity(ctx context.Context, uid string) error {
	defer revocations.forget(uid)
	if authClient == nil {
		return nil
	}
	if err := authClient.RevokeRefreshTokens(ctx, uid); err != nil && !isUserNotFound(err) {
		return err
	}
	if err := authClient.DeleteUser(ctx, uid); err != nil && !isUserNotFound(err) {
		return err
	}
	return nil
}
