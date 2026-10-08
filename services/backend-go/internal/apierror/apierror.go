// Package apierror is the gateway's single error body:
//
//	{"error": "<message for people>", "code": "<stable code for programs>"}
//
// Clients branch on code; error is free to change wording.
package apierror

import "github.com/gin-gonic/gin"

// Abort ends the request with status and the standard error body.
func Abort(c *gin.Context, status int, code, message string) {
	c.AbortWithStatusJSON(status, gin.H{"error": message, "code": code})
}

// AbortWith is Abort plus extra fields a client needs to act on the error
// (e.g. which resources block it). code and error always win.
func AbortWith(c *gin.Context, status int, code, message string, details gin.H) {
	body := gin.H{}
	for k, v := range details {
		body[k] = v
	}
	body["error"], body["code"] = message, code
	c.AbortWithStatusJSON(status, body)
}

// NoRoute answers paths the gateway does not serve.
func NoRoute(c *gin.Context) {
	Abort(c, 404, "not_found", "No such endpoint")
}

// NoMethod answers a known path called with a method it does not support
// (Gin has already set the Allow header).
func NoMethod(c *gin.Context) {
	Abort(c, 405, "method_not_allowed", "Method not allowed for this endpoint")
}
