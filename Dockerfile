# ==================== 构建阶段 ====================
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build
COPY pom.xml .
# 预下载依赖（利用 Docker 缓存层）
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# ==================== 运行阶段 ====================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=mysql"]
