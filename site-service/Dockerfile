FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# All POMs (Maven needs the full reactor structure)
COPY pom.xml .
COPY saga-transaction-lib/pom.xml saga-transaction-lib/
COPY site-service/pom.xml site-service/
COPY uaa-service/pom.xml uaa-service/

# Sources only for modules needed by site-service
COPY saga-transaction-lib/src saga-transaction-lib/src/
COPY site-service/src site-service/src/

RUN mvn package -pl site-service -am -Dmaven.test.skip=true -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copier le JAR exécutable (exclure *-plain.jar qui n'a pas de Main-Class)
COPY --from=build /build/site-service/target/site-service-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
