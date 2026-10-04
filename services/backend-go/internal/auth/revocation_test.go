package auth

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	"firebase.google.com/go/v4/auth"
	"github.com/skolab/backend-go/internal/middleware"
	"golang.org/x/time/rate"
)

var errNotFound = errors.New("user not found")

// fakeFirebase stands in for *auth.Client.
type fakeFirebase struct {
	authTime   int64 // seconds; the token's sign-in time
	validAfter int64 // milliseconds; the account's revocation point
	disabled   bool
	getUserErr error
	badToken   bool
	provider   string // Firebase sign_in_provider; "" means a federated default
	unverified bool   // email_verified=false
	getUsers   int
	revoked    []string
	deleted    []string
}

func (f *fakeFirebase) VerifyIDToken(_ context.Context, token string) (*auth.Token, error) {
	if f.badToken {
		return nil, errors.New("bad signature")
	}
	return &auth.Token{UID: "ada", AuthTime: f.authTime,
		Firebase: auth.FirebaseInfo{SignInProvider: f.provider},
		Claims:   map[string]interface{}{"email_verified": !f.unverified}}, nil
}

func (f *fakeFirebase) GetUser(_ context.Context, uid string) (*auth.UserRecord, error) {
	f.getUsers++
	if f.getUserErr != nil {
		return nil, f.getUserErr
	}
	return &auth.UserRecord{UserInfo: &auth.UserInfo{UID: uid}, Disabled: f.disabled, TokensValidAfterMillis: f.validAfter}, nil
}

func (f *fakeFirebase) RevokeRefreshTokens(_ context.Context, uid string) error {
	f.revoked = append(f.revoked, uid)
	return f.getUserErr
}

func (f *fakeFirebase) DeleteUser(_ context.Context, uid string) error {
	f.deleted = append(f.deleted, uid)
	return f.getUserErr
}

// withFirebase installs the fake, a fresh cache with a controllable clock,
// and release mode.
func withFirebase(t *testing.T, fake *fakeFirebase) *time.Time {
	t.Helper()
	t.Setenv("GIN_MODE", "release")
	savedClient, savedCache, savedNotFound, savedThrottle := authClient, revocations, isUserNotFound, failedLogins
	clock := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	authClient = fake
	revocations = &revocationCache{entries: map[string]userStatus{}, now: func() time.Time { return clock }}
	isUserNotFound = func(err error) bool { return errors.Is(err, errNotFound) }
	failedLogins = middleware.NewRateLimiter(rate.Every(15*time.Second), 20)
	t.Cleanup(func() {
		authClient, revocations, isUserNotFound, failedLogins = savedClient, savedCache, savedNotFound, savedThrottle
	})
	return &clock
}

func request(t *testing.T) int {
	t.Helper()
	return do(t, newTestRouter(), "Bearer token").Code
}

func TestValidSessionIsCheckedOnceThenCached(t *testing.T) {
	fake := &fakeFirebase{authTime: 1000, validAfter: 500_000}
	withFirebase(t, fake)
	for i := 0; i < 5; i++ {
		if code := request(t); code != http.StatusOK {
			t.Fatalf("request %d: status %d", i, code)
		}
	}
	if fake.getUsers != 1 {
		t.Fatalf("Firebase account lookups = %d, want 1 (cached)", fake.getUsers)
	}
}

func TestRevokedDisabledAndDeletedAccountsAreRejected(t *testing.T) {
	cases := map[string]*fakeFirebase{
		"signed in before revocation": {authTime: 1000, validAfter: 2_000_000},
		"account disabled":            {authTime: 1000, disabled: true},
		"account deleted":             {authTime: 1000, getUserErr: errNotFound},
	}
	for name, fake := range cases {
		t.Run(name, func(t *testing.T) {
			withFirebase(t, fake)
			if code := request(t); code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want 401", code)
			}
		})
	}
}

func TestRevocationTakesEffectWhenTheCacheExpires(t *testing.T) {
	fake := &fakeFirebase{authTime: 1000, validAfter: 0}
	clock := withFirebase(t, fake)
	if code := request(t); code != http.StatusOK {
		t.Fatalf("status = %d", code)
	}
	fake.validAfter = 2_000_000 // "sign out everywhere"
	*clock = clock.Add(userStatusTTL)
	if code := request(t); code != http.StatusUnauthorized {
		t.Fatalf("after TTL: status = %d, want 401", code)
	}
}

func TestSocketSessionUsesOriginalSignInTime(t *testing.T) {
	fake := &fakeFirebase{authTime: 1000}
	clock := withFirebase(t, fake)
	if err := CheckSession(context.Background(), "member", 1000); err != nil {
		t.Fatal(err)
	}
	fake.validAfter = 2_000_000
	*clock = clock.Add(userStatusTTL)
	if err := CheckSession(context.Background(), "member", 1000); !errors.Is(err, errRevoked) {
		t.Fatalf("revoked socket allowed: %v", err)
	}
	if err := CheckSession(context.Background(), "member", 0); !errors.Is(err, errStatusUnavailable) {
		t.Fatal("legacy ticket without sign-in time allowed")
	}
}

func TestFirebaseOutage(t *testing.T) {
	down := errors.New("connection refused")

	t.Run("known user served from a recent status", func(t *testing.T) {
		fake := &fakeFirebase{authTime: 1000}
		clock := withFirebase(t, fake)
		request(t)
		fake.getUserErr = down
		*clock = clock.Add(userStatusTTL + time.Minute)
		if code := request(t); code != http.StatusOK {
			t.Fatalf("status = %d, want 200 (stale but recent)", code)
		}
	})
	t.Run("status too old fails closed", func(t *testing.T) {
		fake := &fakeFirebase{authTime: 1000}
		clock := withFirebase(t, fake)
		request(t)
		fake.getUserErr = down
		*clock = clock.Add(userStatusMaxStale)
		if code := request(t); code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d, want 503", code)
		}
	})
	t.Run("unknown user fails closed", func(t *testing.T) {
		withFirebase(t, &fakeFirebase{authTime: 1000, getUserErr: down})
		if code := request(t); code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d, want 503", code)
		}
	})
}

func TestInvalidTokenNeverReachesAccountLookup(t *testing.T) {
	fake := &fakeFirebase{badToken: true}
	withFirebase(t, fake)
	if code := request(t); code != http.StatusUnauthorized || fake.getUsers != 0 {
		t.Fatalf("status = %d, lookups = %d", code, fake.getUsers)
	}
}

func TestDeleteIdentityEndsSessionsAndDeletesTheAccount(t *testing.T) {
	fake := &fakeFirebase{authTime: 1000}
	withFirebase(t, fake)
	request(t) // caches ada's status
	if err := DeleteIdentity(context.Background(), "ada"); err != nil {
		t.Fatal(err)
	}
	if len(fake.revoked) != 1 || len(fake.deleted) != 1 {
		t.Fatalf("revoked = %v, deleted = %v", fake.revoked, fake.deleted)
	}
	if _, cached := revocations.entries["ada"]; cached {
		t.Fatal("deleted user's cached status must be dropped")
	}
	// Retrying after a partial failure is safe: already-deleted is success.
	fake.getUserErr = errNotFound
	if err := DeleteIdentity(context.Background(), "ada"); err != nil {
		t.Fatalf("idempotent retry: %v", err)
	}
	fake.getUserErr = errors.New("firebase down")
	if err := DeleteIdentity(context.Background(), "ada"); err == nil {
		t.Fatal("a real Firebase failure must be reported")
	}
}

func TestDeleteIdentityWithoutFirebaseIsANoOp(t *testing.T) {
	withNilClient(t)
	if err := DeleteIdentity(context.Background(), "ada"); err != nil {
		t.Fatal(err)
	}
}
