package auth

import (
	"context"
	"log"
	"net/http"
	"strings"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth"
	"github.com/gin-gonic/gin"
)

var authClient *auth.Client

// InitFirebase initializes the Firebase Admin app in Go.
// A missing or invalid credential is treated as a warning (not fatal) so the
// gateway can serve non-authenticated endpoints even in dev/CI environments.
// Protected endpoints check for nil authClient and bypass verification with a
// dev_user placeholder when credentials are absent.
func InitFirebase() {
	app, err := firebase.NewApp(context.Background(), nil)
	if err != nil {
		log.Printf("WARNING: Firebase app init failed (%v) — auth middleware will use dev_user fallback\n", err)
		return
	}
	client, err := app.Auth(context.Background())
	if err != nil {
		log.Printf("WARNING: Firebase auth client unavailable (%v) — auth middleware will use dev_user fallback\n", err)
		return
	}
	authClient = client
	log.Println("Firebase Auth initialized successfully.")
}

// VerifyUser is a Gin middleware that extracts and validates the Firebase JWT
func VerifyUser() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Missing or invalid Authorization header"})
			return
		}

		idToken := strings.TrimPrefix(authHeader, "Bearer ")

		if authClient == nil {
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
}
