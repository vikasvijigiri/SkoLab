package telemetry

import (
	"context"
	"runtime/metrics"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// PoolStats is the subset of *pgxpool.Stat the gateway exports. An interface
// so tests can supply fixed values; pgxpool.Stat has no public constructor.
type PoolStats interface {
	AcquiredConns() int32
	IdleConns() int32
	ConstructingConns() int32
	MaxConns() int32
	EmptyAcquireCount() int64
}

var (
	stateAcquired     = metric.WithAttributes(attribute.String("state", "acquired"))
	stateIdle         = metric.WithAttributes(attribute.String("state", "idle"))
	stateConstructing = metric.WithAttributes(attribute.String("state", "constructing"))
)

// ObserveDBPool exports the "saturation" golden signal for the Postgres pool.
// stat returns nil while the pool is unavailable; nothing is reported then.
// Values are read at collection time (every 30 s), never per request.
func (t *Telemetry) ObserveDBPool(stat func() PoolStats) error {
	meter := t.Metrics.Meter("skolab.db")
	conns, err := meter.Int64ObservableUpDownCounter("skolab.db.pool.connections", metric.WithUnit("{connection}"),
		metric.WithDescription("Pool connections by state"))
	if err != nil {
		return err
	}
	limit, err := meter.Int64ObservableUpDownCounter("skolab.db.pool.max_connections", metric.WithUnit("{connection}"),
		metric.WithDescription("Configured pool size"))
	if err != nil {
		return err
	}
	waits, err := meter.Int64ObservableCounter("skolab.db.pool.acquire_waits", metric.WithUnit("{acquire}"),
		metric.WithDescription("Acquires that waited because the pool was empty"))
	if err != nil {
		return err
	}
	_, err = meter.RegisterCallback(func(_ context.Context, o metric.Observer) error {
		s := stat()
		if s == nil {
			return nil
		}
		o.ObserveInt64(conns, int64(s.AcquiredConns()), stateAcquired)
		o.ObserveInt64(conns, int64(s.IdleConns()), stateIdle)
		o.ObserveInt64(conns, int64(s.ConstructingConns()), stateConstructing)
		o.ObserveInt64(limit, int64(s.MaxConns()))
		o.ObserveInt64(waits, s.EmptyAcquireCount())
		return nil
	}, conns, limit, waits)
	return err
}

// ObserveRuntime exports goroutine count and live heap. runtime/metrics reads
// are cheap and, unlike runtime.ReadMemStats, never stop the world.
func (t *Telemetry) ObserveRuntime() error {
	meter := t.Metrics.Meter("skolab.runtime")
	goroutines, err := meter.Int64ObservableUpDownCounter("skolab.runtime.goroutines", metric.WithUnit("{goroutine}"))
	if err != nil {
		return err
	}
	heap, err := meter.Int64ObservableUpDownCounter("skolab.runtime.heap", metric.WithUnit("By"),
		metric.WithDescription("Bytes occupied by live and not-yet-swept heap objects"))
	if err != nil {
		return err
	}
	_, err = meter.RegisterCallback(func(_ context.Context, o metric.Observer) error {
		samples := []metrics.Sample{{Name: "/sched/goroutines:goroutines"}, {Name: "/memory/classes/heap/objects:bytes"}}
		metrics.Read(samples)
		o.ObserveInt64(goroutines, int64(samples[0].Value.Uint64()))
		o.ObserveInt64(heap, int64(samples[1].Value.Uint64()))
		return nil
	}, goroutines, heap)
	return err
}
