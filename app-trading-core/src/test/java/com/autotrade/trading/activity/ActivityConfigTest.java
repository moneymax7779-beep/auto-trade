package com.autotrade.trading.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.trading.AuthProperties;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Code review 23 Nov 2026: the beans wire up as trading-core starts them, on with sign-in and off without. */
class ActivityConfigTest {

    @Configuration
    @EnableConfigurationProperties({AuthProperties.class, ActivityProperties.class})
    static class Engine {
        /** The engine's pool as Spring Boot makes it (never connected here: the activity pool starts lazily). */
        @Bean(destroyMethod = "close")
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none");
            ds.setUsername("u");
            ds.setPassword("p");
            return ds;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Engine.class, ActivityConfig.class);

    @Test
    void onWithSignInWithItsOwnPoolAndOneDataSourceBeanOnly() {
        runner.withPropertyValues("autotrade.auth.mode=oidc", "autotrade.activity.enabled=",
                        "autotrade.activity.trusted-proxies=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ActivityRecorder.class).enabled()).isTrue();
            assertThat(ctx).hasSingleBean(DataSource.class);
            assertThat(ctx.getBean(ActivityDb.class).dataSource()).isNotSameAs(ctx.getBean(DataSource.class));
            assertThat(((HikariDataSource) ctx.getBean(ActivityDb.class).dataSource()).getMaximumPoolSize())
                    .isEqualTo(ActivityDb.POOL);
            assertThat(ctx).hasSingleBean(ActivityWeb.class).hasSingleBean(ActivityReport.class);
        });
    }

    @Test
    void offWithoutSignInUnlessAskedFor() {
        runner.run(ctx -> assertThat(ctx.getBean(ActivityRecorder.class).enabled()).isFalse());
        runner.withPropertyValues("autotrade.activity.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(ActivityRecorder.class).enabled()).isTrue());
    }
}
