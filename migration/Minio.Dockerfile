FROM --platform=$BUILDPLATFORM golang:1.24-alpine AS build
RUN apk add --no-cache git ca-certificates
WORKDIR /src
RUN git init && git remote add origin https://github.com/minio/minio.git && git fetch --depth 1 origin 9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a && git checkout FETCH_HEAD
ARG TARGETARCH
RUN --mount=type=cache,id=marketing-go-mod,target=/go/pkg/mod --mount=type=cache,id=marketing-go-build,target=/root/.cache/go-build CGO_ENABLED=0 GOOS=linux GOARCH=$TARGETARCH go build -p 4 -trimpath -o /minio .
FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl
COPY --from=build /minio /usr/bin/minio
COPY --from=build /src/LICENSE /usr/share/licenses/minio/LICENSE
LABEL org.opencontainers.image.source="https://github.com/minio/minio" org.opencontainers.image.revision="9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a" org.opencontainers.image.licenses="AGPL-3.0"
EXPOSE 9000 9001
ENTRYPOINT ["/usr/bin/minio"]
