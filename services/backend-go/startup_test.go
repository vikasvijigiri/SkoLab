package main

import (
	"errors"
	"testing"
	"time"
)

func TestDatabaseStartupRecoversTransientFailureAndRefusesPersistentFailure(t *testing.T) {
	unavailable := errors.New("database unavailable")
	for _, failures := range []int{0, 1, 3} {
		attempts := 0
		waits := []time.Duration{}
		err := retryDatabaseInitialization(func() error {
			attempts++
			if attempts <= failures {
				return unavailable
			}
			return nil
		}, func(d time.Duration) { waits = append(waits, d) })
		if failures == 3 {
			if !errors.Is(err, unavailable) || attempts != 3 {
				t.Fatal("permanent failure admitted startup")
			}
		} else if err != nil || attempts != failures+1 {
			t.Fatal("transient failure did not recover")
		}
		if len(waits) != attempts-1 {
			t.Fatal("startup retried without bounded backoff")
		}
	}
}
