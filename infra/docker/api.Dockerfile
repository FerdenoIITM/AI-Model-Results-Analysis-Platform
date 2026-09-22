# API image — Java Spring Boot (scaffold)
# Build context: repository root
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# COPY apps/api /workspace/apps/api
# RUN mvn -f apps/api/pom.xml -pl api-bootstrap -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# COPY --from=build /workspace/apps/api/api-bootstrap/target/*.jar app.jar
# EXPOSE 8080
# ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["echo", "api image scaffold — build not implemented yet"]
