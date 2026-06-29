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

// InitFirebase initializes the Firebase Admin app in Go
func InitFirebase() {
	app, err := firebase.NewApp(context.Background(), nil)
	if err != nil {
		log.Fatalf("error initializing app: %v\n", err)
	}
	client, err := app.Auth(context.Background())
	if err != nil {
		log.Fatalf("error getting Auth client: %v\n", err)
	}
	authClient = client
	log.Println("Successfully initialized Firebase Auth in Go Gateway.")
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
