# Prefer building via infra/docker/api.Dockerfile from the repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . /workspace/apps/api
RUN mvn -f /workspace/apps/api/pom.xml -pl api-bootstrap -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/apps/api/api-bootstrap/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
