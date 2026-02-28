FROM maven:3.8.8-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:8-jre
WORKDIR /app
RUN addgroup --system app && adduser --system --ingroup app app
COPY --from=build /workspace/target/log-viewer-0.1.0.jar /app/app.jar
COPY app-data/servers.yaml /app/app-data/servers.yaml
ENV APP_PORT=8080
ENV CONFIG_FILE=/app/app-data/servers.yaml
ENV JAVA_OPTS=""
EXPOSE 8080
USER app
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
