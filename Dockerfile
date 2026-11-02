FROM node:24-alpine AS frontend
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./
COPY backend/src ./src
COPY --from=frontend /ui/dist ./src/main/resources/static
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S desk && adduser -S desk -G desk
WORKDIR /app
COPY --from=backend /build/target/service-desk-0.1.0.jar app.jar
USER desk
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
