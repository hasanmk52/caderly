package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** CLAUDE.md §6 A05: pinned settings that fail silently if the key is misspelled or dropped. MockMvc is on only so
 * this shares the suite's common Spring context instead of starting its own. */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class ProxyAndActuatorConfigTest {

    @Autowired private Environment environment;
    @Autowired private MockMvc mockMvc;

    @Test
    void forwardHeadersStrategy_isNative_soLinksBehindTheProxyAreHttps() {
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void actuatorExposure_isHealthAndPrometheusOnly() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
    }

    @Test
    void prometheusEndpoint_asAnAnonymousVisitor_isNotServed() throws Exception {
        // The scrape endpoint is meant for a loopback-only management port in deployment; on the
        // application port it must stay behind the deny-by-default security chain.
        int status = mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(200);
    }
}
