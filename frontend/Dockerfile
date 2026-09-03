# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Browser-facing URLs, passed by docker-compose.yml at the repo root.
ARG BACKEND_PUBLIC_URL
ARG KEYCLOAK_PUBLIC_URL

# Render src/environments/environment.ts.template into src/environments/environment.ts,
# overwriting the localhost defaults used by local `ng serve` with the real values for this
# build, before `ng build` picks the file up.
RUN sed \
      -e "s#__BACKEND_PUBLIC_URL__#${BACKEND_PUBLIC_URL}#g" \
      -e "s#__KEYCLOAK_PUBLIC_URL__#${KEYCLOAK_PUBLIC_URL}#g" \
      src/environments/environment.ts.template > src/environments/environment.ts

RUN npx ng build

# ---- Runtime stage ----
FROM nginx:alpine

COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
