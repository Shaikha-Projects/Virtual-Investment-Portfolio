FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/index.html frontend/vite.config.js ./
COPY frontend/public ./public
COPY frontend/src ./src
# Public build-time routes only. Never pass secrets as VITE_* variables.
ENV VITE_AUTH_BASE_URL=/api/auth \
    VITE_INVESTMENT_BASE_URL=/api/investment \
    VITE_NOTIFICATION_BASE_URL=/api/notification
RUN npm run build

FROM nginx:stable-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80 443
