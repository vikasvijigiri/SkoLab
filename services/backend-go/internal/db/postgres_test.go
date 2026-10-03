package db

import "testing"

func TestNormalizeURL(t *testing.T) {
	for in, want := range map[string]string{
		"postgresql+asyncpg://u:p@h:5432/d":  "postgresql://u:p@h:5432/d",
		"postgres+asyncpg://u:p@h/d":         "postgresql://u:p@h/d",
		"postgresql://u:p@h/d":               "postgresql://u:p@h/d",
		"postgres://u:p@h/d?sslmode=require": "postgres://u:p@h/d?sslmode=require",
	} {
		if got := NormalizeURL(in); got != want {
			t.Fatalf("NormalizeURL(%q) = %q, want %q", in, got, want)
		}
	}
}
