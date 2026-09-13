# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# The test suite runs on the CI runner before this image is built. Running it
# again here would only repeat the same slice tests and lengthen every build.
RUN mvn -B -q clean package -DskipTests


FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache dumb-init wget \
 && addgroup -S app \
 && adduser -S -G app app

WORKDIR /app

COPY --from=build --chown=app:app /build/target/app.jar ./app.jar

ARG VERSION=local
ARG COMMIT=unknown

ENV APP_VERSION=$VERSION \
    APP_COMMIT=$COMMIT

USER app

EXPOSE 8080

# dumb-init is PID 1 so SIGTERM from ECS reaches the JVM and Spring's graceful
# shutdown actually runs. MaxRAMPercentage is 70 rather than 75 because
# Lettuce's Netty buffers are off-heap and are not counted by that flag.
ENTRYPOINT ["dumb-init", "--", "java", \
            "-XX:MaxRAMPercentage=70", \
            "-XX:+UseSerialGC", \
            "-jar", "/app/app.jar"]
