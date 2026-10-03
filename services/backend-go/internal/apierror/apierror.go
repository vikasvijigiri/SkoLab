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
