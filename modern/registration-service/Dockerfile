# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# Optional Maven Central mirror (e.g. when repo.maven.apache.org rate-limits the build host).
ARG MAVEN_MIRROR_URL=
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi \
 && export MVNW_REPOURL="${MAVEN_MIRROR_URL}" \
 && ./mvnw -B -q dependency:go-offline
COPY src src
RUN export MVNW_REPOURL="${MAVEN_MIRROR_URL}" && ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/registration-service.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
