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

# Run mod tidy to generate go.sum and build
RUN go mod tidy
RUN go build -o main .

FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/main .

EXPOSE 8080
CMD ["./main"]
