// Package alerts relays Sentry alert webhooks to Slack.
//
// Sentry's own Slack integration needs a paid plan; a Sentry internal
// integration can instead POST each alert here. The request is accepted only
// with a valid Sentry-Hook-Signature (HMAC-SHA256 of the raw body under the
// integration's client secret), and only the alert's title and issue link
// are forwarded: stack traces, request data and user details never reach Slack.
package alerts

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
)

const maxTitle = 300

// Relay forwards verified Sentry alerts to a Slack incoming webhook.
type Relay struct {
	Secret string // the Sentry integration's client secret
	Slack  string // Slack incoming webhook URL
	Client *http.Client
}

// Configured reports whether both ends are set.
func (r Relay) Configured() bool { return r.Secret != "" && r.Slack != "" }

// SlackWebhook returns url if it is a Slack incoming webhook, else "".
func SlackWebhook(url string) string {
	if strings.HasPrefix(url, "https://hooks.slack.com/services/") {
		return url
	}
	return ""
}

// Handler answers POST /hooks/sentry.
func (r Relay) Handler() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !r.Configured() {
			apierror.Abort(c, http.StatusServiceUnavailable, "not_configured", "Sentry alert relay is not configured")
			return
		}
		body, err := io.ReadAll(c.Request.Body)
		if err != nil {
			apierror.Abort(c, http.StatusBadRequest, "invalid_body", "Request body could not be read")
			return
		}
		if !validSignature(r.Secret, body, c.GetHeader("Sentry-Hook-Signature")) {
			apierror.Abort(c, http.StatusUnauthorized, "invalid_signature", "Missing or invalid Sentry-Hook-Signature")
			return
		}
		text, ok, err := message(c.GetHeader("Sentry-Hook-Resource"), body)
		if err != nil {
			apierror.Abort(c, http.StatusBadRequest, "invalid_body", "Request body must be a Sentry webhook payload")
			return
		}
		if !ok { // installation and other lifecycle events: acknowledged, nothing to post
			c.Status(http.StatusNoContent)
			return
		}
		if err := r.post(c.Request.Context(), text); err != nil {
			slog.Error("sentry relay: Slack delivery failed", "err", err)
			apierror.Abort(c, http.StatusBadGateway, "slack_unavailable", "Slack did not accept the alert")
			return
		}
		c.Status(http.StatusNoContent)
	}
}

func validSignature(secret string, body []byte, signature string) bool {
	want, err := hex.DecodeString(strings.TrimSpace(signature))
	if err != nil || len(want) != sha256.Size {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return hmac.Equal(mac.Sum(nil), want)
}

// payload holds only the fields that are forwarded.
type payload struct {
	Action string `json:"action"`
	Data   struct {
		Event *struct {
			Title  string `json:"title"`
			WebURL string `json:"web_url"`
			Level  string `json:"level"`
		} `json:"event"`
		TriggeredRule    string `json:"triggered_rule"`
		DescriptionTitle string `json:"description_title"`
		WebURL           string `json:"web_url"`
	} `json:"data"`
}

// message builds the Slack text for alert resources; ok is false for
// resources that carry no alert.
func message(resource string, body []byte) (text string, ok bool, err error) {
	var p payload
	if err := json.Unmarshal(body, &p); err != nil {
		return "", false, err
	}
	switch resource {
	case "event_alert":
		if p.Data.Event == nil {
			return "", false, nil
		}
		title := clip(p.Data.Event.Title)
		if p.Data.Event.Level != "" {
			title = "[" + clip(p.Data.Event.Level) + "] " + title
		}
		return "Sentry: " + title + "\n" + safeURL(p.Data.Event.WebURL), true, nil
	case "metric_alert":
		if p.Action == "resolved" {
			return "Sentry resolved: " + clip(p.Data.DescriptionTitle) + "\n" + safeURL(p.Data.WebURL), true, nil
		}
		return "Sentry " + clip(p.Action) + ": " + clip(p.Data.DescriptionTitle) + "\n" + safeURL(p.Data.WebURL), true, nil
	default:
		return "", false, nil
	}
}

func clip(s string) string {
	s = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return ' '
		}
		return r
	}, s)
	if utf8.RuneCountInString(s) > maxTitle {
		s = string([]rune(s)[:maxTitle]) + "…"
	}
	return s
}

// safeURL keeps only https links into Sentry.
func safeURL(raw string) string {
	u, err := url.Parse(raw)
	if err == nil && u.Scheme == "https" && (u.Host == "sentry.io" || strings.HasSuffix(u.Host, ".sentry.io")) {
		return raw
	}
	return "(no issue link)"
}

func (r Relay) post(ctx context.Context, text string) error {
	body, _ := json.Marshal(map[string]any{"text": text, "unfurl_links": false})
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.Slack, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	client := r.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		// The URL embeds the webhook secret; report only that the call failed.
		return errors.New("slack webhook unreachable")
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("slack answered HTTP %d", resp.StatusCode)
	}
	return nil
}
