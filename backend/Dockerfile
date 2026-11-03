FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN mvn -pl backend -DskipTests dependency:go-offline
COPY backend backend
RUN mvn -pl backend -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.49.0-noble
WORKDIR /app
COPY --from=build /workspace/backend/target/webcollector-backend-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /data/assets /data/capture-work
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
