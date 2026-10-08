package com.marketing.media;

import io.minio.*;
import java.io.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AssetStore {
  private final MinioClient client;
  private final String bucket;
  private final java.nio.file.Path local;

  public AssetStore(Environment e) {
    local =
        "filesystem".equals(e.getProperty("app.storage-backend"))
            ? java.nio.file.Path.of(e.getProperty("app.storage-directory", ".runtime/media"))
                .toAbsolutePath()
                .normalize()
            : null;
    if (local != null && !e.getProperty("app.demo", Boolean.class, false))
      throw new IllegalStateException("Filesystem storage is restricted to demo mode");
    client =
        MinioClient.builder()
            .endpoint(e.getRequiredProperty("app.storage-endpoint"))
            .credentials(
                e.getRequiredProperty("app.storage-access-key"),
                e.getRequiredProperty("app.storage-secret-key"))
            .build();
    bucket = e.getRequiredProperty("app.storage-bucket");
  }

  public void put(String key, byte[] bytes) {
    try {
      if (local != null) {
        var path = path(key);
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.write(path, bytes);
        return;
      }
      if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      client.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(
                  new ByteArrayInputStream(bytes), bytes.length, -1)
              .contentType("image/png")
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("Private asset storage unavailable");
    }
  }

  private java.nio.file.Path path(String key) {
    var p = local.resolve(key).normalize();
    if (!p.startsWith(local)) throw new IllegalArgumentException("Invalid asset path");
    return p;
  }

  public byte[] get(String key) {
    if (local != null) {
      try {
        return java.nio.file.Files.readAllBytes(path(key));
      } catch (Exception e) {
        throw new IllegalStateException("Cannot read private asset");
      }
    }
    try (var in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
      return in.readAllBytes();
    } catch (Exception e) {
      throw new IllegalStateException("Asset cannot be read");
    }
  }
}
