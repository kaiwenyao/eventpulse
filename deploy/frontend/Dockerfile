# Static SPA build served by nginx; /api is proxied to the backend so cookies
# stay same-origin (HttpOnly refresh cookie + SameSite=Lax).
# Build context is the repo root (see docker-compose.yml). Unit/e2e tests are
# not run in this image — they need jsdom/Chromium and are documented in README.
FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
