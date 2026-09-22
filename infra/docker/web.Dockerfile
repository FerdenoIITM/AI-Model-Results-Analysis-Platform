# Web image — React + Vite → nginx (scaffold)
# Build context: repository root
FROM node:20-alpine AS build
WORKDIR /workspace
# COPY apps/web/package.json apps/web/package-lock.json* ./apps/web/
# RUN cd apps/web && npm ci
# COPY apps/web ./apps/web
# RUN cd apps/web && npm run build

FROM nginx:1.27-alpine
# COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
# EXPOSE 80
CMD ["echo", "web image scaffold — build not implemented yet"]
