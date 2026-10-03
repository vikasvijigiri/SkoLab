# Keep in step with go.mod's go directive (CI's image job builds this file, so
# drift fails the PR). GOTOOLCHAIN=local: the build uses this image's Go and
# never downloads a different toolchain at build time.
FROM golang:1.26-alpine AS builder

WORKDIR /app

ENV GOTOOLCHAIN=local

# Copy module files
COPY go.mod go.sum ./
RUN go mod download

# Copy source
COPY . .

# `go mod tidy` was here previously, but go.sum is already committed and
# verified by CI (.github/workflows/ci.yml's `go vet`/`go test`) — running
# tidy at build time can silently rewrite the lockfile inside the image
# instead of building against the exact, reviewed dependency set (2026-09-26
# reliability audit). `go mod verify` checks the downloaded modules against
# the committed go.sum without changing it.
RUN go mod verify
RUN go build -o main .

# Pinned, not :latest, so a rebuild cannot silently change the base OS.
FROM alpine:3.24
WORKDIR /app

# Non-root, mirroring services/backend/Dockerfile's uid/gid convention.
RUN addgroup -g 10001 -S skolab
RUN adduser -u 10001 -S -G skolab skolab
COPY --from=builder --chown=skolab:skolab /app/main .
USER skolab

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/gateway-health || exit 1
CMD ["./main"]
