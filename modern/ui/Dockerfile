# syntax=docker/dockerfile:1
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_MOCK=0
ENV VITE_MOCK=$VITE_MOCK
RUN npm run build

FROM nginx:1.27-alpine
ENV LICENCE_UPSTREAM=licence-service:8081 \
    DEMERIT_UPSTREAM=demerit-service:8082 \
    REGISTRATION_UPSTREAM=registration-service:8083 \
    NOTIFICATION_UPSTREAM=notification-service:8084 \
    DNS_RESOLVER=127.0.0.11
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 5173
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://127.0.0.1:5173/healthz || exit 1
