package auth

import (
	"net/http"
	"os"
	"strings"
	"time"

	"firebase.google.com/go/v4/auth"
	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/middleware"
	"github.com/skolab/backend-go/internal/security"
	"golang.org/x/time/rate"
)

// Failed-login throttling: each rejected token (bad, expired or revoked)
// spends one token from the caller IP's bucket -- 20 failures, refilling one
// every 15s. An IP that runs out is refused before any verification work, so
// a token-stuffing client cannot keep this service busy or keep probing.
var failedLogins = middleware.NewRateLimiter(rate.Every(15*time.Second), 20)

func throttled(c *gin.Context) bool {
	if failedLogins.Limiter(security.ClientIP(c)).Tokens() >= 1 {
		return false
	}
	security.Record(c, security.Event{Name: security.AuthThrottled, Outcome: security.Throttled})
	c.Header("Retry-After", "15")
	c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
		"error": "Too many failed sign-in attempts; try again shortly", "code": "auth_throttled",
	})
	return true
}

// reject answers 401 for a failed credential and charges the failure to the
// caller's IP.
func reject(c *gin.Context, event, code, message string) {
	failedLogins.Limiter(security.ClientIP(c)).Allow()
	security.Record(c, security.Event{Name: event, Outcome: security.Denied, Reason: code})
	c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": message, "code": code})
}

// requireVerifiedEmail is on unless AUTH_REQUIRE_VERIFIED_EMAIL=false (a
// rollout switch, not a long-term setting).
func requireVerifiedEmail() bool {
	return !strings.EqualFold(os.Getenv("AUTH_REQUIRE_VERIFIED_EMAIL"), "false")
}

// accountPolicy refuses identities the backend must not act for: anonymous
// Firebase sessions (no person behind them) and email/password accounts
// whose address was never confirmed (anyone can register someone else's
// address). Federated providers vouch for the identity themselves. Answers
// 403: the token is genuine, the account is not yet allowed.
func accountPolicy(c *gin.Context, token *auth.Token) bool {
	switch token.Firebase.SignInProvider {
	case "anonymous":
		security.Record(c, security.Event{Name: security.AuthAnonymousRefused, Outcome: security.Denied, UserID: token.UID})
		c.AbortWithStatusJSON(http.StatusForbidden, gin.H{
			"error": "Sign in with an account to use this service", "code": "anonymous_not_allowed",
		})
		return false
	case "password":
		if verified, _ := token.Claims["email_verified"].(bool); !verified && requireVerifiedEmail() {
			security.Record(c, security.Event{Name: security.AuthEmailUnverified, Outcome: security.Denied, UserID: token.UID})
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{
				"error": "Verify your email address to continue", "code": "email_unverified",
			})
			return false
		}
	}
	return true
}
