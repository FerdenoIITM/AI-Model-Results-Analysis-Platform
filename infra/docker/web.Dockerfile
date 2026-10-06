# Web image — Vite build served by nginx
FROM node:20-alpine AS build
WORKDIR /workspace
COPY apps/web/package.json ./apps/web/
RUN cd apps/web && npm install
COPY apps/web ./apps/web
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN cd apps/web && npm run build

FROM nginx:1.27-alpine
COPY infra/docker/web-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
