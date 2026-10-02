.PHONY: help dev-backend dev-go lint-python test-python test-go slo-rules

help:
	@echo "SkoLab commands:"
	@echo "  make dev-backend       Run the FastAPI service"
	@echo "  make dev-go            Run the Go gateway"
	@echo "  make lint-python       Run Ruff"
	@echo "  make test-python       Run FastAPI tests"
	@echo "  make test-go           Run Go vet and race tests"
	@echo "  make slo-rules         Regenerate, check and unit-test SLO alert rules (needs promtool)"

dev-backend:
	cd services/backend && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

dev-go:
	cd services/backend-go && go run main.go

lint-python:
	cd services/backend && ruff check .

test-python:
	cd services/backend && pytest -q

test-go:
	cd services/backend-go && go vet ./... && go test ./... -race

slo-rules:
	cd services/observability/slo && python generate.py && promtool check rules skolab-slo.rules.yml && promtool test rules skolab-slo.test.yml
