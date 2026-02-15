# API Gateway

Spring Cloud Gateway service — single entry point for all client traffic.

## What it does
- **Routing:** Static routes to backend services via env vars (`PROFILE_SERVICE_URL`, `REGRET_STREAM_URL`)
- **CORS:** Allows `boredsoftwaredeveloper.xyz` and `localhost:4200`
- **Circuit Breaking:** Resilience4j per route with fallback responses
- **Request Logging:** Method, path, status, latency on every request
- **Error Handling:** Structured JSON error responses

## Run locally
```bash
./gradlew bootRun
# Gateway on http://localhost:8080
# Expects Profile Service on http://localhost:8081
# Expects Regret Stream on http://localhost:8082
```

## Environment variables
| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | Gateway listen port |
| `PROFILE_SERVICE_URL` | `http://localhost:8081` | Profile Service Cloud Run URL |
| `REGRET_STREAM_URL` | `http://localhost:8082` | Regret Stream Service Cloud Run URL |

## Build & test
```bash
./gradlew clean check    # compile + test + coverage verification
./gradlew bootJar        # build executable JAR
```

## Docker
```bash
docker build -t api-gateway .
docker run -p 8080:8080 \
  -e PROFILE_SERVICE_URL=http://host.docker.internal:8081 \
  -e REGRET_STREAM_URL=http://host.docker.internal:8082 \
  api-gateway
```
