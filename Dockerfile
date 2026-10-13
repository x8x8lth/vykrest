# ---------- 1. збірка jar ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- 2. запуск ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/vkrst-store-bot.jar app.jar
# фото товарів і екранів лежать у репозиторії, тому не зникають при перезапуску
COPY images ./images
# data/ — каталог товарів (products.tsv)
RUN mkdir -p /app/data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
