FROM maven:3.9-eclipse-temurin-17 AS build
# Optional Maven Central mirror, e.g. --build-arg MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2
ARG MAVEN_MIRROR_URL=""
WORKDIR /src
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; fi
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
