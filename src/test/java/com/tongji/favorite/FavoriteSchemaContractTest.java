package com.tongji.favorite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FavoriteSchemaContractTest {

    @Test
    void schemaContainsFavoritePrimaryKeyAndCursorIndex() throws Exception {
        String schema = Files.readString(Path.of("db/schema.sql"));

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS user_favorite");
        assertThat(schema).contains("PRIMARY KEY (user_id, post_id)");
        assertThat(schema).contains(
                "KEY idx_user_favorite_page (user_id, created_at DESC, post_id DESC)"
        );
    }
}
