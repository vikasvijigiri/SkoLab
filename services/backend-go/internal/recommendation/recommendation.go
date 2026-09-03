// Package recommendation serves the CoLab peer/collaborator autocomplete
// endpoints (/api/v1/recommendations/peers*). Ported from the Python backend's
// app/domains/recommendation/service.py as part of the "Python is LLM-only"
// migration (decisions/0002, decisions/0008) — this is pure Postgres CRUD with
// no AI, so it belongs on the Go edge, not behind the proxy.
//
// Faithful-port note: users.email is Fernet-encrypted at rest with a random IV
// (see services/backend/app/db/encrypted_type.py), so equality/ILIKE matching
// on it never succeeded in the Python version either. This port matches on the
// plaintext columns (display_name, username, phone) only. Restoring email
// matching needs a deterministic blind-index column and is tracked as its own
// migration — see decisions/0008.
package recommendation

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"os"
	"sort"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/db"
)

// maxCheckIdentifiers bounds a single check-registered call. The Python version
// had no cap, which made the endpoint an unbounded membership-enumeration
// oracle. Device contact lists rarely exceed this.
const maxCheckIdentifiers = 200

// emailBlindIndex mirrors services/backend/app/db/blind_index.py: hex
// HMAC-SHA256 of the normalised (trimmed, lowercased) address under
// EMAIL_BLIND_INDEX_KEY. Empty key ⇒ "" ⇒ caller skips email matching. Keep the
// normalisation identical to the Python side or matches silently break.
func emailBlindIndex(email string) string {
	key := os.Getenv("EMAIL_BLIND_INDEX_KEY")
	if key == "" || email == "" {
		return ""
	}
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(strings.ToLower(strings.TrimSpace(email))))
	return hex.EncodeToString(mac.Sum(nil))
}

// PeerRecommendation mirrors app/domains/recommendation/schemas.py:PeerRecommendation.
type PeerRecommendation struct {
	UID            *string `json:"uid"`
	Name           string  `json:"name"`
	Username       *string `json:"username"`
	Email          *string `json:"email"`
	Phone          *string `json:"phone"`
	ResearchFocus  *string `json:"research_focus"`
	IsRegistered   bool    `json:"is_registered"`
	RelevanceScore float64 `json:"relevance_score"`
}

type logPeerInviteRequest struct {
	UserID    string  `json:"user_id"`
	PeerEmail *string `json:"peer_email"`
	PeerPhone *string `json:"peer_phone"`
	PeerUID   *string `json:"peer_uid"`
}

type checkRegisteredRequest struct {
	Emails []string `json:"emails"`
	Phones []string `json:"phones"`
}

type checkRegisteredResponse struct {
	RegisteredEmails []string `json:"registered_emails"`
	RegisteredPhones []string `json:"registered_phones"`
}

func strPtr(s string) *string { return &s }

func min2(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

// GetPeerRecommendations handles GET /api/v1/recommendations/peers.
func GetPeerRecommendations(c *gin.Context) {
	query := strings.TrimSpace(c.Query("query"))
	userID := c.Query("user_id")
	ctx := c.Request.Context()

	out := []PeerRecommendation{}
	if query == "" || db.Pool == nil {
		c.JSON(http.StatusOK, out)
		return
	}
	like := "%" + strings.ToLower(query) + "%"
	qLower := strings.ToLower(query)

	// 1. Requesting user's own focus + circle, for ranking (optional).
	userFocus := ""
	circle := map[string]bool{}
	if userID != "" {
		var f *string
		_ = db.Pool.QueryRow(ctx,
			`SELECT research_focus FROM users WHERE id = $1`, userID).Scan(&f)
		if f != nil {
			userFocus = strings.ToLower(*f)
		}
		if rows, err := db.Pool.Query(ctx,
			`SELECT peer_id FROM user_circles WHERE user_id = $1`, userID); err == nil {
			for rows.Next() {
				var pid string
				if rows.Scan(&pid) == nil {
					circle[pid] = true
				}
			}
			rows.Close()
		}
	}

	focusWords := map[string]bool{}
	for _, w := range strings.Fields(userFocus) {
		focusWords[w] = true
	}
	overlapBonus := func(other string, maxBonus float64, perWord float64) float64 {
		if userFocus == "" || other == "" {
			return 0
		}
		n := 0
		for _, w := range strings.Fields(strings.ToLower(other)) {
			if focusWords[w] {
				n++
			}
		}
		if n == 0 {
			return 0
		}
		return min2(maxBonus, float64(n)*perWord)
	}

	seenNames := map[string]bool{}

	// 2. Registered users — plaintext columns only (email is encrypted).
	rows, err := db.Pool.Query(ctx, `
		SELECT id, display_name, username, phone, research_focus
		FROM users
		WHERE display_name ILIKE $1 OR username ILIKE $1 OR phone ILIKE $1
		LIMIT 20
	`, like)
	if err == nil {
		for rows.Next() {
			var id, name string
			var username, phone, focus *string
			if rows.Scan(&id, &name, &username, &phone, &focus) != nil {
				continue
			}
			uname := ""
			if username != nil {
				uname = *username
			}
			uphone := ""
			if phone != nil {
				uphone = *phone
			}
			ufocus := ""
			if focus != nil {
				ufocus = *focus
			}
			score := 0.5
			switch {
			case qLower == strings.ToLower(uname) || qLower == uphone:
				score += 0.4
			case strings.Contains(strings.ToLower(uname), qLower) || strings.Contains(uphone, qLower):
				score += 0.3
			case strings.Contains(strings.ToLower(name), qLower):
				score += 0.2
			}
			score += overlapBonus(ufocus, 0.15, 0.03)
			if circle[id] {
				score += 0.1
			}
			idCopy := id
			rec := PeerRecommendation{
				UID:            &idCopy,
				Name:           name,
				IsRegistered:   true,
				RelevanceScore: min2(1.0, score),
			}
			if uname != "" {
				rec.Username = strPtr(uname)
			}
			if uphone != "" {
				rec.Phone = strPtr(uphone)
			}
			if ufocus != "" {
				rec.ResearchFocus = strPtr(ufocus)
			}
			// Email deliberately omitted: it is encrypted at rest and this is a
			// transitional-auth endpoint — do not surface another user's email
			// through autocomplete.
			out = append(out, rec)
			seenNames[strings.ToLower(name)] = true
		}
		rows.Close()
	}

	// 3. Cached OpenAlex researcher profiles (unregistered scientists).
	prows, err := db.Pool.Query(ctx, `
		SELECT display_name, field_of_study
		FROM researcher_profiles
		WHERE display_name ILIKE $1 OR field_of_study ILIKE $1
		LIMIT 20
	`, like)
	if err == nil {
		for prows.Next() {
			var name string
			var field *string
			if prows.Scan(&name, &field) != nil {
				continue
			}
			if seenNames[strings.ToLower(name)] {
				continue
			}
			ffield := ""
			if field != nil {
				ffield = *field
			}
			score := 0.4
			if strings.Contains(strings.ToLower(name), qLower) {
				score += 0.2
			}
			if ffield != "" && strings.Contains(strings.ToLower(ffield), qLower) {
				score += 0.1
			}
			score += overlapBonus(ffield, 0.1, 0.02)
			rec := PeerRecommendation{
				Name:           name,
				Email:          strPtr(strings.ReplaceAll(strings.ToLower(name), " ", "") + "@university.edu"),
				IsRegistered:   false,
				RelevanceScore: min2(1.0, score),
			}
			if ffield != "" {
				rec.ResearchFocus = strPtr(ffield)
			}
			out = append(out, rec)
		}
		prows.Close()
	}

	sort.SliceStable(out, func(i, j int) bool {
		return out[i].RelevanceScore > out[j].RelevanceScore
	})
	if len(out) > 10 {
		out = out[:10]
	}
	c.JSON(http.StatusOK, out)
}

// LogPeerInvite handles POST /api/v1/recommendations/peers/invite.
func LogPeerInvite(c *gin.Context) {
	var req logPeerInviteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	ctx := c.Request.Context()

	peerID := ""
	if req.PeerUID != nil {
		peerID = *req.PeerUID
	}
	// Resolving a peer by email is not possible while users.email is encrypted
	// (the Python version had the same limitation). peer_uid is the only path.
	if peerID == "" || req.UserID == "" || db.Pool == nil {
		c.JSON(http.StatusOK, gin.H{"success": false})
		return
	}

	tag, err := db.Pool.Exec(ctx, `
		INSERT INTO user_circles (user_id, peer_id, relationship_type, relevance_score, spark_sessions_count)
		VALUES ($1, $2, 'manual', 0.7, 1)
		ON CONFLICT (user_id, peer_id) DO UPDATE SET
			spark_sessions_count = user_circles.spark_sessions_count + 1,
			relevance_score = LEAST(1.0, user_circles.relevance_score + 0.1)
	`, req.UserID, peerID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"success": false})
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": tag.RowsAffected() > 0})
}

// CheckRegisteredPeers handles POST /api/v1/recommendations/peers/check-registered.
func CheckRegisteredPeers(c *gin.Context) {
	var req checkRegisteredRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if len(req.Emails)+len(req.Phones) > maxCheckIdentifiers {
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "too many identifiers in one request; cap is 200",
		})
		return
	}

	resp := checkRegisteredResponse{RegisteredEmails: []string{}, RegisteredPhones: []string{}}
	ctx := c.Request.Context()

	phones := make([]string, 0, len(req.Phones))
	for _, p := range req.Phones {
		if p = strings.TrimSpace(p); p != "" {
			phones = append(phones, p)
		}
	}

	// Email matching via the deterministic blind index (users.email_bidx) — the
	// encrypted email column itself cannot be equality-matched. Skipped when
	// EMAIL_BLIND_INDEX_KEY is unset (emailBlindIndex returns "").
	bidxToEmail := make(map[string]string, len(req.Emails))
	bidxList := make([]string, 0, len(req.Emails))
	for _, e := range req.Emails {
		if b := emailBlindIndex(e); b != "" {
			if _, seen := bidxToEmail[b]; !seen {
				bidxToEmail[b] = strings.TrimSpace(e)
				bidxList = append(bidxList, b)
			}
		}
	}

	if db.Pool == nil || (len(phones) == 0 && len(bidxList) == 0) {
		c.JSON(http.StatusOK, resp)
		return
	}

	if len(phones) > 0 {
		rows, err := db.Pool.Query(ctx,
			`SELECT phone FROM users WHERE phone = ANY($1)`, phones)
		if err == nil {
			for rows.Next() {
				var ph *string
				if rows.Scan(&ph) == nil && ph != nil && *ph != "" {
					resp.RegisteredPhones = append(resp.RegisteredPhones, *ph)
				}
			}
			rows.Close()
		}
	}

	if len(bidxList) > 0 {
		rows, err := db.Pool.Query(ctx,
			`SELECT email_bidx FROM users WHERE email_bidx = ANY($1)`, bidxList)
		if err == nil {
			for rows.Next() {
				var b *string
				if rows.Scan(&b) == nil && b != nil {
					if orig, ok := bidxToEmail[*b]; ok {
						resp.RegisteredEmails = append(resp.RegisteredEmails, orig)
					}
				}
			}
			rows.Close()
		}
	}

	c.JSON(http.StatusOK, resp)
}
