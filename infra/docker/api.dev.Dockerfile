# Dev API image — source is mounted at runtime; restarts on .java changes.
FROM maven:3.9-eclipse-temurin-21

RUN apt-get update \
  && apt-get install -y --no-install-recommends inotify-tools \
  && rm -rf /var/lib/apt/lists/*

COPY infra/docker/api-dev-entrypoint.sh /usr/local/bin/api-dev-entrypoint.sh
RUN chmod +x /usr/local/bin/api-dev-entrypoint.sh

WORKDIR /workspace/apps/api
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/api-dev-entrypoint.sh"]
