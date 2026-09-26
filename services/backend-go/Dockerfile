FROM golang:1.25-alpine AS builder

WORKDIR /app

ENV GOTOOLCHAIN=auto

# Copy module files
COPY go.mod ./
# If go.sum exists, copy it. Otherwise skip.
COPY go.su[m] ./
RUN go mod download || true

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

FROM alpine:latest
WORKDIR /app

# Non-root, mirroring services/backend/Dockerfile's uid/gid convention.
RUN addgroup -g 10001 -S skolab && \
    adduser -u 10001 -S -G skolab skolab
COPY --from=builder --chown=skolab:skolab /app/main .
USER skolab

EXPOSE 8080
CMD ["./main"]
