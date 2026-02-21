FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# All POMs (Maven needs the full reactor structure)
COPY pom.xml .
COPY saga-transaction-lib/pom.xml saga-transaction-lib/
COPY site-service/pom.xml site-service/
COPY uaa-service/pom.xml uaa-service/

# Sources only for uaa-service
COPY uaa-service/src uaa-service/src/

RUN mvn package -pl uaa-service -am -Dmaven.test.skip=true -B

# Sélectionner le JAR exécutable (le plus gros = fat jar avec Main-Class, pas le *-plain.jar)
RUN cd /build/uaa-service/target && \
    cp $(ls -S *.jar 2>/dev/null | grep -v '\.original$' | head -1) /build/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /build/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
