# auto-trade execution workers (live execution L1). Build context is the repository root:
#   mvn -q -DskipTests -pl app-execution -am clean install && docker compose --profile execution build execution
FROM eclipse-temurin:21-jre
ARG CODE_VERSION=unknown
ENV AUTOTRADE_CODE_VERSION=${CODE_VERSION} \
    TZ=Asia/Kolkata \
    JAVA_TOOL_OPTIONS="-Xmx512m -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
RUN useradd --system --uid 10001 autotrade
COPY app-execution/target/app-execution-0.1.0-SNAPSHOT.jar /app/app.jar
USER autotrade
EXPOSE 8096
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD wget -qO- http://127.0.0.1:8096/actuator/health >/dev/null 2>&1 || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
