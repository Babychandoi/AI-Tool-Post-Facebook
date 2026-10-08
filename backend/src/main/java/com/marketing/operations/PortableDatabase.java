package com.marketing.operations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@Profile("portable")
@ConditionalOnProperty(name = "app.mode", havingValue = "api")
public class PortableDatabase {
  @Bean(destroyMethod = "close")
  EmbeddedPostgres portablePostgres() throws Exception {
    return EmbeddedPostgres.builder()
        .setPort(55433)
        .setDataDirectory(".runtime/postgres")
        .setCleanDataDirectory(false)
        .setServerConfig("listen_addresses", "127.0.0.1")
        .start();
  }

  @Bean
  DataSource dataSource(EmbeddedPostgres pg) {
    return pg.getPostgresDatabase();
  }
}
