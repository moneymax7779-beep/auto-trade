package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Spring can build every bean in the application: a bean with more than one constructor must mark the one to inject
 * with {@code @Autowired}, or startup fails with "No default constructor found". No unit test starts the context, so
 * this guards the one mistake that only showed when the server started (MarketHours, 20 Oct).
 */
class BeanConstructorsTest {

    @Test
    void everyBeanWithSeveralConstructorsMarksTheOneToInject() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Component.class));     // also @RestController, @Service, @Configuration
        List<String> ambiguous = new ArrayList<>();
        int beans = 0;
        for (var candidate : scan.findCandidateComponents("com.autotrade")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            beans++;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            boolean marked = Arrays.stream(constructors).anyMatch(c -> c.isAnnotationPresent(Autowired.class));
            boolean noArgs = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            if (constructors.length > 1 && !marked && !noArgs) {
                ambiguous.add(type.getName());
            }
        }
        assertThat(beans).as("beans scanned").isGreaterThan(10);
        assertThat(ambiguous).as("beans Spring cannot construct").isEmpty();
    }
}
