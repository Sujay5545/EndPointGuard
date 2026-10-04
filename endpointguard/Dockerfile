FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
	&& addgroup -S endpointguard \
	&& adduser -S -G endpointguard endpointguard
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER endpointguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
