# syntax=docker/dockerfile:1.7

FROM node:22-alpine AS build
WORKDIR /workspace/apps/web
COPY apps/web/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY apps/web ./
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM nginx:1.27-alpine
COPY infra/nginx/omnivid.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=10 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
