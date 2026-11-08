package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.stereotype.Component;

/**
 * Every Spring bean of the execution service can be constructed (8 Nov 2026: KotakSessionSource and
 * UpstoxSessionSource had two constructors and no {@code @Autowired}, so the worker would not have started; no test
 * starts the Spring context, so nothing caught it). Spring picks the only constructor, the {@code @Autowired} one, or
 * a no-argument one; anything else fails at startup.
 */
class SpringConstructorsTest {

    @Test
    void everyBeanHasAConstructorSpringCanChoose() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(Component.class));
        List<String> ambiguous = new ArrayList<>();
        int beans = 0;
        for (var candidate : scan.findCandidateComponents("com.autotrade.exec.app")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            beans++;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            boolean ok = constructors.length == 1
                    || Arrays.stream(constructors).filter(c -> c.isAnnotationPresent(Autowired.class)).count() == 1
                    || Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            if (!ok) {
                ambiguous.add(type.getSimpleName());
            }
        }
        assertThat(beans).as("beans found").isGreaterThan(10);
        assertThat(ambiguous).as("beans with several constructors and none marked @Autowired").isEmpty();
    }
}
