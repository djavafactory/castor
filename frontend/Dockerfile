FROM node:24.22.0-trixie-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# VITE_* values are bundled into the browser build: no secrets here
ARG VITE_PORT_CODE=westports
ARG VITE_DEMO_LOGIN=false
ARG VITE_SATELLITE_TILE_URL=https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg
ENV VITE_APP_ENV=dev \
    VITE_API_URL=/api \
    VITE_PORT_CODE=$VITE_PORT_CODE \
    VITE_DEMO_LOGIN=$VITE_DEMO_LOGIN \
    VITE_SATELLITE_TILE_URL=$VITE_SATELLITE_TILE_URL
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
