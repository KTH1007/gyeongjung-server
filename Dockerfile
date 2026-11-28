# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY build-logic build-logic
COPY config config
COPY app app
COPY shared shared
COPY collector collector
COPY ops ops
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:bootJar \
    && java -Djarmode=tools -jar app/build/libs/app.jar extract --layers --destination /extracted

FROM eclipse-temurin:25-jre
ENV TZ=UTC \
    LOGGING_FILE_NAME=/var/log/gyeongjung/gyeongjung.log
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home app \
    && mkdir -p /var/log/gyeongjung \
    && chown app:app /var/log/gyeongjung
WORKDIR /app
# 바뀌는 빈도가 낮은 레이어를 위에 둬서 코드만 바뀌면 의존성 레이어가 캐시를 쓴다
COPY --from=builder /extracted/dependencies/ ./
COPY --from=builder /extracted/spring-boot-loader/ ./
COPY --from=builder /extracted/snapshot-dependencies/ ./
COPY --from=builder /extracted/application/ ./
# 같은 서버에서 우리 이미지만 골라 정리하는 기준이다 (ops/prune-images.sh)
LABEL project=gyeongjung
USER app
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
ENTRYPOINT ["java", "-jar", "app.jar"]
