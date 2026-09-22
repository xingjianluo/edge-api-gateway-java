package com.github.xingjianluo.edgeapigatewayjava.demo;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 仅在 demo profile 下启动本机假上游，方便 mvn spring-boot:run -Dspring-boot.run.profiles=demo 验收。 */
@Configuration
@Profile("demo")
public class DemoConfiguration {

    private final DemoUpstreamServer stable = new DemoUpstreamServer(18081, "stable");
    private final DemoUpstreamServer flaky = new DemoUpstreamServer(18082, "flaky");

    public DemoConfiguration() {
        stable.start();
        flaky.start();
    }

    @PreDestroy
    public void shutdown() {
        stable.stop();
        flaky.stop();
    }
}
