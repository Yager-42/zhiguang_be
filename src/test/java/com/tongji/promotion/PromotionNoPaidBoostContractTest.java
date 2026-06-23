package com.tongji.promotion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionNoPaidBoostContractTest {

    @Test
    void activeBackendContainsNoPaidBoostSurface() throws Exception {
        String activeBackendText = Files.walk(Path.of("src/main"))
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.toString().replace('\\', '/');
                    return name.endsWith(".java") || name.endsWith(".xml")
                            || name.endsWith(".yml") || name.endsWith(".yaml");
                })
                .map(PromotionNoPaidBoostContractTest::readUnchecked)
                .reduce(Files.readString(Path.of("db/schema.sql")), (left, right) -> left + "\n" + right);

        assertThat(activeBackendText)
                .doesNotContain("PaidBoost")
                .doesNotContain("paidBoost")
                .doesNotContain("paid_boost")
                .doesNotContain("paid boost")
                .doesNotContain("weight slot")
                .doesNotContain("weight_slot");
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }
}
