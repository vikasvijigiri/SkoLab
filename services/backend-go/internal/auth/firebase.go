package auth

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	firebase "firebase.google.com/go/v4"
	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/security"
)

// authClient is the Firebase Auth client (nil until InitFirebase succeeds).
var authClient identityProvider

// releaseMode reports whether the gateway is running in a deployed
// configuration. It reads GIN_MODE directly rather than gin.Mode() so that the
// answer does not depend on main() having run: middleware built inside a test
// behaves exactly as one built at startup. main.go:30 already gates structured
// logging on the same variable, and .env.example ships GIN_MODE=release.
func releaseMode() bool {
	return os.Getenv("GIN_MODE") == "release"
}

// InitFirebase initializes the Firebase Admin app in Go.
// A missing or invalid credential is not fatal here, so the gateway can still
// serve its unauthenticated endpoints. What happens on a protected route then
// depends on the mode: dev/CI falls back to a dev_user placeholder, release
// refuses the request (see VerifyUser). Startup logs at ERROR in release
// because in that mode every protected route is about to start failing.
func InitFirebase() {
	logAtSeverity := slog.Warn
	if releaseMode() {
		logAtSeverity = slog.Error
	}
	app, err := firebase.NewApp(context.Background(), nil)
	if err != nil {
		logAtSeverity("Firebase app init failed — protected routes will be refused in release, dev_user in dev/CI", "err", err)
		return
	}
	client, err := app.Auth(context.Background())
	if err != nil {
		logAtSeverity("Firebase auth client unavailable — protected routes will be refused in release, dev_user in dev/CI", "err", err)
		return
	}
	authClient = client
	slog.Info("Firebase Auth initialized successfully.")
}

// verifyTokenAndSetUser validates idToken against Firebase and, on success,
// stores the verified UID in the Gin context before calling c.Next(). Every
// failure mode -- missing client in release, missing client in dev/CI, an
// invalid, expired, or revoked token -- is handled here. Browser WebSockets
// use a short-lived ticket rather than placing a Firebase bearer token in a
// query string; see internal/websocket/tickets.go.
func verifyTokenAndSetUser(c *gin.Context, idToken string) {
	if authClient == nil {
		// Fail closed in release. The dev_user fallback exists for dev and
		// CI, but nothing used to gate it, so a Firebase misconfiguration
		// in a deployed gateway silently served every protected route as
		// one shared identity -- and because the header check above only
		// requires the string "Bearer " to be present, any garbage token
		// reached this branch.
		if releaseMode() {
			slog.Error("Firebase auth is unavailable and GIN_MODE=release — refusing the request instead of falling back to dev_user")
			security.Record(c, security.Event{Name: security.AuthUnavailable, Outcome: security.Failed, Reason: "firebase_not_configured"})
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Authentication is temporarily unavailable"})
			return
		}
		slog.Warn("authClient is nil, bypassing auth for development.")
		c.Set("user_id", "dev_user")
		c.Next()
		return
	}

	// Signature, expiry, issuer and audience are verified locally against
	// Google's cached public keys. A valid token can still belong to a
	// session revoked since it was minted ("sign out everywhere", password
	// change, account disabled or deleted), so revocation is then checked
	// against a briefly cached account status -- same rule as Firebase's
	// check-revoked, without a network round trip on every request.
	ctx, cancel := context.WithTimeout(c.Request.Context(), 10*time.Second)
	defer cancel()
	token, err := authClient.VerifyIDToken(ctx, idToken)
	if err != nil {
		reject(c, security.AuthTokenInvalid, "token_invalid", "Invalid or expired Firebase token")
		return
	}
	// Account policy is a local check on the token, so it runs before the
	// (possibly network-backed) revocation lookup.
	if !accountPolicy(c, token) {
		return
	}
	if err := revocations.check(ctx, authClient, token.UID, token.AuthTime); err != nil {
		if errors.Is(err, errRevoked) {
			reject(c, security.AuthSessionRevoked, "session_revoked", "Session is no longer valid; sign in again")
			return
		}
		slog.Error("account status check unavailable", "err", err)
		security.Record(c, security.Event{Name: security.AuthUnavailable, Outcome: security.Failed, UserID: token.UID})
		c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Authentication is temporarily unavailable"})
		return
	}

	// Set the verified user ID in the Gin context
	c.Set("user_id", token.UID)
	c.Next()
}

// VerifyUser is a Gin middleware that extracts and validates the Firebase JWT
// from the Authorization header.
func VerifyUser() gin.HandlerFunc {
	return func(c *gin.Context) {
		if throttled(c) {
			return
		}
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Missing or invalid Authorization header"})
			return
		}
		idToken := strings.TrimPrefix(authHeader, "Bearer ")
		verifyTokenAndSetUser(c, idToken)
	}
}
