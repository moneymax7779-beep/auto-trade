package com.autotrade.trading.activity;

import com.autotrade.trading.AuthProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** User activity recording: on with sign-in unless {@code autotrade.activity.enabled} says otherwise. */
@Configuration
class ActivityConfig {

    /** Activity's own small pool beside the engine's (never a DataSource bean). */
    @Bean(destroyMethod = "close")
    ActivityDb activityDb(DataSource dataSource) {
        return new ActivityDb(dataSource);
    }

    @Bean(destroyMethod = "close")
    ActivityRecorder activityRecorder(ActivityDb db, AuthProperties auth, ActivityProperties props) {
        return props.on(auth.oidc()) ? ActivityRecorder.start(db.dataSource(), props, Clock.systemUTC())
                : ActivityRecorder.disabled();
    }

    @Bean
    ActivityWeb activityWeb(ActivityRecorder recorder, ActivityProperties props) {
        return new ActivityWeb(recorder, new ClientAddress(props.trustedProxies()));
    }

    @Bean
    ActivityReport activityReport(ActivityDb db) {
        return new ActivityReport(db.dataSource(), Clock.systemUTC());
    }
}
