# Multi-stage build on eclipse-temurin:17 (the maven image is eclipse-temurin:17 plus Maven).
FROM maven:3.9-eclipse-temurin-17 AS build
# Optional Maven Central mirror, e.g. --build-arg MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2
ARG MAVEN_MIRROR_URL=""
WORKDIR /src
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; fi
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run with `mvn verify` outside Docker (they need the legacy fixtures in ../../tests).
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/demerit-service.jar app.jar
EXPOSE 8082
ENV AWS_ENDPOINT_URL=http://localhost:4566 AWS_REGION=ap-southeast-2 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
