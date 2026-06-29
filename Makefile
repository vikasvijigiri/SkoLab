.PHONY: compile-tokens dev-backend dev-go build-android test-load-baseline clean

compile-tokens:
	node shared/skolab-design-system/compile-tokens.js

dev-backend:
	cd services/backend && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

dev-go:
	cd services/backend-go && go run main.go

build-android:
	powershell -ExecutionPolicy Bypass -File scripts/build-and-install.ps1

test-load-baseline:
	k6 run tests/load/baseline.js

clean:
	python tools/clean_caches.py
