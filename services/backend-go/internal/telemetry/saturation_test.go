package telemetry

import (
	"context"
	"testing"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

type fakePool struct{}

func (fakePool) AcquiredConns() int32     { return 4 }
func (fakePool) IdleConns() int32         { return 2 }
func (fakePool) ConstructingConns() int32 { return 1 }
func (fakePool) MaxConns() int32          { return 15 }
func (fakePool) EmptyAcquireCount() int64 { return 7 }

func collect(t *testing.T, tel *Telemetry, read func(context.Context, *metricdata.ResourceMetrics) error) map[string]metricdata.Aggregation {
	t.Helper()
	var data metricdata.ResourceMetrics
	if err := read(context.Background(), &data); err != nil {
		t.Fatal(err)
	}
	out := map[string]metricdata.Aggregation{}
	for _, scope := range data.ScopeMetrics {
		for _, m := range scope.Metrics {
			out[m.Name] = m.Data
		}
	}
	return out
}

func TestDBPoolSaturationMetrics(t *testing.T) {
	tel, reader, _ := setup(t)
	available := true
	if err := tel.ObserveDBPool(func() PoolStats {
		if !available {
			return nil
		}
		return fakePool{}
	}); err != nil {
		t.Fatal(err)
	}
	got := collect(t, tel, reader.Collect)
	byState := map[string]int64{}
	for _, p := range got["skolab.db.pool.connections"].(metricdata.Sum[int64]).DataPoints {
		state, _ := p.Attributes.Value(attribute.Key("state"))
		byState[state.AsString()] = p.Value
	}
	if byState["acquired"] != 4 || byState["idle"] != 2 || byState["constructing"] != 1 {
		t.Fatalf("connections by state = %v", byState)
	}
	if v := got["skolab.db.pool.max_connections"].(metricdata.Sum[int64]).DataPoints[0].Value; v != 15 {
		t.Fatalf("max_connections = %d", v)
	}
	waits := got["skolab.db.pool.acquire_waits"].(metricdata.Sum[int64])
	if !waits.IsMonotonic || waits.DataPoints[0].Value != 7 {
		t.Fatalf("acquire_waits = %+v", waits)
	}

	available = false
	if got := collect(t, tel, reader.Collect); got["skolab.db.pool.connections"] != nil &&
		len(got["skolab.db.pool.connections"].(metricdata.Sum[int64]).DataPoints) != 0 {
		t.Fatal("an unavailable pool must report nothing, not zeros")
	}
}

func TestRuntimeSaturationMetrics(t *testing.T) {
	tel, reader, _ := setup(t)
	if err := tel.ObserveRuntime(); err != nil {
		t.Fatal(err)
	}
	got := collect(t, tel, reader.Collect)
	if v := got["skolab.runtime.goroutines"].(metricdata.Sum[int64]).DataPoints[0].Value; v < 1 {
		t.Fatalf("goroutines = %d", v)
	}
	if v := got["skolab.runtime.heap"].(metricdata.Sum[int64]).DataPoints[0].Value; v <= 0 {
		t.Fatalf("heap = %d", v)
	}
}
