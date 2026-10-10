# Web image — Vite build served by nginx, which also proxies /api (see web-nginx.conf.template)
FROM node:20-alpine AS build
WORKDIR /workspace
COPY apps/web/package.json ./apps/web/
RUN cd apps/web && npm install
COPY apps/web ./apps/web
# Same-origin path: the browser calls /api on whatever host served the page.
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN cd apps/web && npm run build

FROM nginx:1.27-alpine
COPY infra/docker/web-nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
# Only these variables are substituted, so nginx variables such as $host stay intact.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER=^(API_UPSTREAM|NGINX_LOCAL_RESOLVERS)$
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
