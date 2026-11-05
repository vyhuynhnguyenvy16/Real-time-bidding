# RTB Service

Real-time bidding engine (DSP-lite) viết bằng Java 21 và Spring Boot 4.0.x.
Thiết kế chi tiết: spec, kiến trúc, data model, API contract, ADR (xem thư mục `docs/` khi đã được thêm).

## Chạy thử hạ tầng cục bộ

    cp deploy/compose/.env.example deploy/compose/.env   # rồi sửa mật khẩu
    docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env up -d

## Build

    ./mvnw clean verify          # Windows: .\mvnw.cmd clean verify
