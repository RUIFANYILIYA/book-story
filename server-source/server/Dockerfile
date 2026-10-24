# syntax=docker/dockerfile:1
#
# Multi-stage Node image for the ebook library server.
#
# The design premise this file has to preserve is that the runtime tree contains no native module
# and nothing is installed at build time beyond npm itself: no apt packages, no node-gyp, no
# per-platform prebuilds to match. That is what lets the same image build on a workstation and run
# on a NAS without a toolchain, and it is why `node:sqlite`, `node:crypto` scrypt and the built-in
# ICU decoder were chosen over better-sqlite3, argon2 and a chardet dependency.
#
# Build context is the repository root, not server/, because the lockfile lives at the workspace
# root and is what makes the install reproducible.

ARG NODE_BASE=node:24-bookworm-slim

# ---------------------------------------------------------------- dependency manifests
# Copied alone so that a source change does not invalidate the dependency layers.
FROM ${NODE_BASE} AS manifests
WORKDIR /app
COPY package.json package-lock.json ./
COPY server/package.json ./server/package.json
COPY shared/package.json ./shared/package.json

# ---------------------------------------------------------------- build
# A full install, dev dependencies included: the build step runs `tsc`, which `--omit=dev` would
# leave out — and the failure is only an exit-127 "command not found" from a layer that looks
# unrelated to dependencies. Production dependencies are installed separately below.
FROM manifests AS build
RUN npm ci --no-audit --fund=false
COPY shared ./shared
COPY server ./server
RUN npm run build -w server

# ---------------------------------------------------------------- production dependencies
FROM manifests AS prod-deps
# The CI job `image-dependencies` asserts this install produces zero *.node binaries, which is the
# premise that lets the same image build here and run on a NAS with no toolchain — checked, not
# hoped for.
RUN npm ci --omit=dev --no-audit --fund=false

# ---------------------------------------------------------------- runtime
FROM ${NODE_BASE} AS runtime
ENV NODE_ENV=production \
    EBOOK_DATA_DIR=/data/db \
    EBOOK_LIBRARY_DIR=/data/library \
    EBOOK_IMPORT_DIR=/data/library/imports \
    EBOOK_HOST=0.0.0.0 \
    EBOOK_PORT=8787

WORKDIR /app
COPY --from=prod-deps /app/node_modules ./node_modules
COPY --from=build /app/server/dist ./server/dist
COPY server/package.json ./server/package.json
# Migrations and the admin console are read from disk at runtime, not bundled.
COPY server/migrations ./server/migrations
COPY server/public ./server/public

# The container runs unprivileged. The image's own `node` user already exists, so no useradd and no
# shell are needed — which is also why there is no shell-based healthcheck.
#
# Creating and chowning the mount points here is mandatory, not tidiness: Docker seeds a fresh
# named volume with the ownership of the image's directory, so a volume mounted at a path that does
# not exist yet, or exists as root, leaves SQLite unable to write and reporting only SQLITE_CANTOPEN.
RUN mkdir -p /data/db /data/library/blobs /data/library/covers /data/library/tmp/uploads \
             /data/library/trash /data/library/imports /data/library/backups \
 && chown -R node:node /data
USER node

EXPOSE 8787
# No VOLUME directive on purpose: it would create anonymous volumes for anyone running the image
# without compose, which is a well-known way to lose data on the next `docker run`.
CMD ["node", "/app/server/dist/main.js"]
