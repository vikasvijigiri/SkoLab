# Multi-stage build for the Next.js frontend, mirroring the minimal-image
# approach already used for the Go gateway and Python backend Dockerfiles.
#
# apps/web is an npm workspace member of this monorepo, so the build context
# is the REPO ROOT (not apps/web) -- npm needs the root package.json and
# lockfile to resolve the workspace correctly, exactly like CI's own
# `npm ci` step does. See render.yaml: dockerContext is "." for this service.
#
# `output: "standalone"` (next.config.ts) traces the real runtime dependency
# graph via @vercel/nft into `.next/standalone`, which for an npm-workspace
# build lands at `apps/web/.next/standalone/apps/web/server.js` -- the
# `public/` and `.next/static/` directories are NOT included in that trace
# and must be copied in manually (Next's own documented monorepo pattern).
#
# NEXT_PUBLIC_* variables are inlined into the client bundle at BUILD time,
# not read at runtime -- they must arrive as Docker build ARGs, not just
# service-level env vars. Render passes its service env vars to the Docker
# build as build args automatically; the ARG/ENV pairs below are declared
# explicitly so the build fails loudly (Next warns on an accessed-but-unset
# NEXT_PUBLIC_ var) rather than silently baking in an empty string.

FROM node:22-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
RUN npm ci

FROM node:22-slim AS builder
WORKDIR /app
# Only the root node_modules exists -- npm workspaces hoist every dependency
# there by default; apps/web/node_modules is never created at all, and an
# earlier version of this COPY that assumed otherwise failed the real Render
# build with "apps/web/node_modules": not found.
COPY --from=deps /app/node_modules ./node_modules
COPY . .

ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_FIREBASE_API_KEY
ARG NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN
ARG NEXT_PUBLIC_FIREBASE_PROJECT_ID
ARG NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET
ARG NEXT_PUBLIC_FIREBASE_APP_ID
ARG NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID
ARG NEXT_PUBLIC_SENTRY_DSN
ARG NEXT_PUBLIC_SITE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL \
    NEXT_PUBLIC_FIREBASE_API_KEY=$NEXT_PUBLIC_FIREBASE_API_KEY \
    NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=$NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN \
    NEXT_PUBLIC_FIREBASE_PROJECT_ID=$NEXT_PUBLIC_FIREBASE_PROJECT_ID \
    NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=$NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET \
    NEXT_PUBLIC_FIREBASE_APP_ID=$NEXT_PUBLIC_FIREBASE_APP_ID \
    NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID=$NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID \
    NEXT_PUBLIC_SENTRY_DSN=$NEXT_PUBLIC_SENTRY_DSN \
    NEXT_PUBLIC_SITE_URL=$NEXT_PUBLIC_SITE_URL

RUN npm run build --workspace web

FROM node:22-slim AS runner
WORKDIR /app
ENV NODE_ENV=production \
    PORT=3000 \
    HOSTNAME=0.0.0.0

RUN groupadd -g 10001 skolab && \
    useradd -u 10001 -g skolab -s /bin/bash -m skolab

COPY --from=builder --chown=skolab:skolab /app/apps/web/.next/standalone ./
COPY --from=builder --chown=skolab:skolab /app/apps/web/.next/static ./apps/web/.next/static
# No `public/` COPY: the directory doesn't exist right now (icon.tsx and
# apple-icon.tsx are code-generated, not static files under public/). Add it
# back the moment a real static asset lands in apps/web/public/.

USER skolab
EXPOSE 3000

CMD ["node", "apps/web/server.js"]
