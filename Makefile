.PHONY: help compile-tokens dev-backend dev-go build-android lint-python test-python test-go verify-contracts test-rules test-load-baseline test-load-rampup test-load-soak test-load-spike

help:
	@echo "SkoLab commands:"
	@echo "  make dev-backend       Run the FastAPI service"
	@echo "  make dev-go            Run the Go gateway"
	@echo "  make lint-python       Run Ruff"
	@echo "  make test-python       Run FastAPI tests"
	@echo "  make test-go           Run Go vet and race tests"
	@echo "  make verify-contracts  Check API contract compatibility"
	@echo "  make test-rules        Run Firebase rules tests"
	@echo "  make build-android     Generate tokens and build Android"

compile-tokens:
	node packages/design-system/compile-tokens.js

dev-backend:
	cd services/backend && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

dev-go:
	cd services/backend-go && go run main.go

build-android:
	powershell -ExecutionPolicy Bypass -File scripts/build-and-install.ps1

lint-python:
	cd services/backend && ruff check .

test-python:
	cd services/backend && pytest -q

test-go:
	cd services/backend-go && go vet ./... && go test ./... -race

verify-contracts:
	python scripts/verify_contracts.py

test-rules:
	npm run test:rules

test-load-baseline:
	k6 run tests/load/baseline.js

test-load-rampup:
	k6 run tests/load/ramp_up.js

test-load-soak:
	k6 run tests/load/soak.js

test-load-spike:
	k6 run tests/load/spike.js
