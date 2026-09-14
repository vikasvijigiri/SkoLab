package auth

import (
	"context"
	"log"
	"net/http"
	"os"
	"strings"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth"
	"github.com/gin-gonic/gin"
)

var authClient *auth.Client

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
	level := "WARNING"
	if releaseMode() {
		level = "ERROR"
	}
	app, err := firebase.NewApp(context.Background(), nil)
	if err != nil {
		log.Printf("%s: Firebase app init failed (%v) — protected routes will be refused in release, dev_user in dev/CI\n", level, err)
		return
	}
	client, err := app.Auth(context.Background())
	if err != nil {
		log.Printf("%s: Firebase auth client unavailable (%v) — protected routes will be refused in release, dev_user in dev/CI\n", level, err)
		return
	}
	authClient = client
	log.Println("Firebase Auth initialized successfully.")
}

// verifyTokenAndSetUser validates idToken against Firebase and, on success,
// stores the verified UID in the Gin context before calling c.Next(). Every
// failure mode -- missing client in release, missing client in dev/CI, an
// invalid/expired token -- is handled here so VerifyUser (header) and
// VerifyQueryToken (query string, for routes like a WebSocket upgrade that
// cannot send a custom header) share the exact same verification and
// fail-open/fail-closed behavior; only where the raw token string comes from
// differs between the two callers.
func verifyTokenAndSetUser(c *gin.Context, idToken string) {
	if authClient == nil {
		// Fail closed in release. The dev_user fallback exists for dev and
		// CI, but nothing used to gate it, so a Firebase misconfiguration
		// in a deployed gateway silently served every protected route as
		// one shared identity -- and because the header check above only
		// requires the string "Bearer " to be present, any garbage token
		// reached this branch.
		if releaseMode() {
			log.Println("ERROR: Firebase auth is unavailable and GIN_MODE=release — refusing the request instead of falling back to dev_user")
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Authentication is temporarily unavailable"})
			return
		}
		log.Println("WARNING: authClient is nil, bypassing auth for development.")
		c.Set("user_id", "dev_user")
		c.Next()
		return
	}

	token, err := authClient.VerifyIDToken(context.Background(), idToken)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired Firebase token"})
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
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Missing or invalid Authorization header"})
			return
		}
		idToken := strings.TrimPrefix(authHeader, "Bearer ")
		verifyTokenAndSetUser(c, idToken)
	}
}

// VerifyQueryToken is VerifyUser's counterpart for routes a browser cannot
// attach a custom Authorization header to -- the WebSocket upgrade request
// fired by the browser's own WebSocket API. The token instead travels as a
// query parameter (?token=<firebase-id-token>); verification itself is the
// same underlying Firebase call VerifyUser uses (verifyTokenAndSetUser). Used
// by GET /ws/colab/:workspace_id (main.go) -- any route whose client can
// control real headers should use VerifyUser instead.
func VerifyQueryToken() gin.HandlerFunc {
	return func(c *gin.Context) {
		idToken := strings.TrimSpace(c.Query("token"))
		if idToken == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Missing token query parameter"})
			return
		}
		verifyTokenAndSetUser(c, idToken)
	}
}

// VerifyUserOptional is a transitional middleware for routes that are moving
// behind auth but still have live tokenless clients (installed Android builds
// send no Authorization header today). A valid token sets user_id; a missing or
// invalid token is allowed through with user_id unset. It never aborts.
//
// The dependent handlers must therefore treat user_id as optional and must not
// grant cross-user access on its absence. This is removed — replaced by
// VerifyUser — once client telemetry shows tokenless traffic has fallen off
// (see decisions/0008-recommendation-peers-to-go-gateway.md).
func VerifyUserOptional() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.Next()
			return
		}
		if authClient == nil {
			c.Next()
			return
		}
		idToken := strings.TrimPrefix(authHeader, "Bearer ")
		token, err := authClient.VerifyIDToken(context.Background(), idToken)
		if err != nil {
			c.Next()
			return
		}
		c.Set("user_id", token.UID)
		c.Next()
	}
}
