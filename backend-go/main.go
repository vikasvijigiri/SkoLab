package main

import (
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/auth"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/handlers"
	"github.com/skolab/backend-go/internal/websocket"
)

// ReverseProxy forwards requests to the Python FastAPI backend
func ReverseProxy(target string) gin.HandlerFunc {
	targetUrl, err := url.Parse(target)
	if err != nil {
		log.Fatalf("Invalid target URL: %v", err)
	}

	proxy := httputil.NewSingleHostReverseProxy(targetUrl)

	originalDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		originalDirector(req)
		req.Header.Set("X-Forwarded-Host", req.Header.Get("Host"))
	}

	return func(c *gin.Context) {
		// Let the reverse proxy handle the request
		proxy.ServeHTTP(c.Writer, c.Request)
	}
}

func main() {
	pythonBackendURL := os.Getenv("PYTHON_BACKEND_URL")
	if pythonBackendURL == "" {
		pythonBackendURL = "http://localhost:8001"
	}

	// Initialize Firebase Auth
	auth.InitFirebase()

	// Initialize PostgreSQL
	if err := db.InitDB(); err != nil {
		log.Printf("Failed to initialize PostgreSQL: %v\n", err)
		// Don't fatal yet, just log for local dev without DB
	}
	defer db.CloseDB()

	r := gin.Default()

	// Initialize the WebSocket Hub
	hub := websocket.NewHub()
	go hub.Run()

	// Health check for the Go Gateway
	r.GET("/gateway-health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":  "online",
			"service": "go-gateway",
		})
	})

	// WebSocket Hub endpoint
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		websocket.ServeWs(hub, c)
	})

	// Global Health WebSocket endpoint
	r.GET("/ws/system/health", func(c *gin.Context) {
		websocket.ServeHealthWs(c)
	})

	// Native Go Auth/User Endpoints
	usersAPI := r.Group("/api/v1/users")
	usersAPI.Use(auth.VerifyUser())
	{
		usersAPI.POST("/profile/sync", handlers.SyncUserProfile)
		usersAPI.DELETE("/:userId", handlers.DeleteUser)
		// usersAPI.POST("/:userId/export", handlers.ExportUserData) // Todo: Migrate
	}

	// Proxy all other requests (AI, Teleport) to the existing Python backend on 8001
	r.NoRoute(func(c *gin.Context) {
		// Prevent proxying websockets intended for Python unless Python supports them
		if strings.ToLower(c.GetHeader("Upgrade")) == "websocket" {
			log.Println("Rejecting unhandled websocket upgrade request")
			c.AbortWithStatus(http.StatusNotImplemented)
			return
		}

		ReverseProxy(pythonBackendURL)(c)
	})

	log.Println("Starting Go API Gateway on :8080...")
	log.Printf("Proxying unmatched routes to Python Backend on %s\n", pythonBackendURL)
	
	if err := r.Run(":8080"); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}
