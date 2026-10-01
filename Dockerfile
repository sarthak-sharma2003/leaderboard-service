FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:25-jre
COPY --from=build /src/target/leaderboard-service-*.jar /app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app.jar"]
