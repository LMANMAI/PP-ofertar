FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src/ src/
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
# Matches the timezone the application sets on the JVM, so container logs
# and any shell run inside it agree with the data.
ENV TZ=America/Argentina/Buenos_Aires
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
# Proceso sin privilegios; uploads/ es donde TicketService guarda las fotos.
RUN useradd --system --uid 10001 --no-create-home app && mkdir -p /app/uploads && chown -R app:app /app
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh && chmod +x /entrypoint.sh
EXPOSE 8080
USER app
ENTRYPOINT ["/entrypoint.sh"]
