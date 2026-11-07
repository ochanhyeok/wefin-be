package com.solv.wefin.domain.news.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NewsBatchProperties}의 {@code @Validated} 제약 조건이
 * 실제 Spring Boot binding 시점에 정상 동작하는지 검증한다
 */
class NewsBatchPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class, ValidationAutoConfiguration.class);

    @Test
    @DisplayName("정상 값이면 컨텍스트가 기동된다")
    void validValues_contextStarts() {
        runner.withPropertyValues(
                        "batch.news.crawl-size=100",
                        "batch.news.embedding-size=100",
                        "batch.news.tagging-size=100",
                        "batch.news.clustering-size=100",
                        "batch.news.summary-size=20",
                        "batch.news.rejudge-max-limit=100",
                        "batch.news.rejudge-batch-size=50")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    NewsBatchProperties props = ctx.getBean(NewsBatchProperties.class);
                    assertThat(props.crawlSize()).isEqualTo(100);
                });
    }

    @Test
    @DisplayName("crawl-size=0이면 컨텍스트 기동이 실패한다 (@Min(1) 위반)")
    void zeroSize_contextFails() {
        runner.withPropertyValues("batch.news.crawl-size=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("tagging-size가 @Max(1000)를 초과하면 컨텍스트 기동이 실패한다")
    void exceedMax_contextFails() {
        runner.withPropertyValues("batch.news.tagging-size=1001")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(NewsBatchProperties.class)
    static class Config {
    }
}
