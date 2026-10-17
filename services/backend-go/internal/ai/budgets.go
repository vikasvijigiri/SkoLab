package ai

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/quota"
)

// Budgets charges named daily allowances. The production implementation is
// the shared usage_counters ledger (quota.Allowance); tests use a map.
type Budgets interface {
	// Charge spends cost from key's daily allowance of limit, answering what
	// is left and a refund, or *quota.Exceeded.
	Charge(ctx context.Context, key string, cost, limit int) (left int, refund func(), err error)
	// Left is what remains of key's daily allowance, without charging it.
	Left(ctx context.Context, key string, limit int) int
}

// LedgerBudgets keeps allowances in Postgres (quota.Allowance).
type LedgerBudgets struct{ Pool *pgxpool.Pool }

func (b LedgerBudgets) Charge(ctx context.Context, key string, cost, limit int) (int, func(), error) {
	receipt, left, err := quota.Allowance(ctx, b.Pool, key, cost, limit)
	if err != nil {
		return 0, func() {}, err
	}
	return left, func() { receipt.Refund(context.WithoutCancel(ctx), b.Pool) }, nil
}

func (b LedgerBudgets) Left(ctx context.Context, key string, limit int) int {
	return max(limit-quota.Used(ctx, b.Pool, key), 0)
}

func userKey(uid, feature string) string { return "ai:u:" + uid + ":" + feature }

const (
	modelKey  = "ai:g:model"
	searchKey = "ai:g:search"
)

// spend charges the person's allowance for feature and, when global is set,
// one unit of the provider's shared budget. It answers false after writing
// the refusal (429 with Retry-After, or 503 when the ledger is down).
func spend(c *gin.Context, b Budgets, uid, feature string, perUser int, global string, globalLimit int) (left int, refund func(), ok bool) {
	ctx := c.Request.Context()
	left, refundUser, err := b.Charge(ctx, userKey(uid, feature), 1, perUser)
	if err != nil {
		budgetRefusal(c, err, "ai_daily_limit", "You've used today's allowance for this AI feature. It resets at midnight UTC.")
		return 0, nil, false
	}
	refundGlobal := func() {}
	if global != "" {
		if _, refundGlobal, err = b.Charge(ctx, global, 1, globalLimit); err != nil {
			refundUser()
			budgetRefusal(c, err, "ai_capacity", "SkoLab has used today's AI capacity. Try again tomorrow.")
			return 0, nil, false
		}
	}
	return left, func() { refundGlobal(); refundUser() }, true
}

func budgetRefusal(c *gin.Context, err error, code, message string) {
	var exceeded *quota.Exceeded
	if errors.As(err, &exceeded) {
		c.Header("Retry-After", strconv.Itoa(max(int(exceeded.RetryAfter/time.Second), 1)))
		fail(c, http.StatusTooManyRequests, code, message)
		return
	}
	fail(c, http.StatusServiceUnavailable, "unavailable", "AI features are temporarily unavailable")
}
