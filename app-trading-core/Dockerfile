# auto-trade trading-core (PAPER). Build context is the repository root:
#   mvn -q -DskipTests -pl app-trading-core -am clean install && (cd ui && npm run build)
#   docker compose build trading-core
FROM eclipse-temurin:21-jre
ARG CODE_VERSION=unknown
ENV AUTOTRADE_CODE_VERSION=${CODE_VERSION} \
    TZ=Asia/Kolkata \
    JAVA_TOOL_OPTIONS="-Xmx2g -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
RUN useradd --system --uid 10001 autotrade && mkdir -p /app/.local && chown autotrade /app/.local
COPY app-trading-core/target/app-trading-core-0.1.0-SNAPSHOT.jar /app/app.jar
COPY ui/dist /app/ui/dist
USER autotrade
EXPOSE 8095
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://127.0.0.1:8095/actuator/health >/dev/null 2>&1 || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["--mode=auto"]
