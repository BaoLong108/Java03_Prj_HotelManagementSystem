FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache fontconfig ttf-dejavu && addgroup -S hotel && adduser -S hotel -G hotel
WORKDIR /app
COPY --from=build --chown=hotel:hotel /build/target/hotel-pms-api-0.0.1-SNAPSHOT.jar app.jar
USER hotel
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
