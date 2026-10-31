package com.caderly.caderlyhr.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The Prometheus scrape endpoint is open only on the loopback-only management port, never on the
 * application port that Caddy proxies to the internet.
 */
class ManagementPortMatcherTest {

    private static final int MANAGEMENT_PORT = 8086;

    private final RequestMatcher matcher = SecurityConfig.prometheusOnManagementPort(MANAGEMENT_PORT);

    private static MockHttpServletRequest request(String uri, int localPort) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setLocalPort(localPort);
        return request;
    }

    @Test
    void matches_prometheusOnTheManagementPort_isTrue() {
        assertThat(matcher.matches(request("/actuator/prometheus", MANAGEMENT_PORT))).isTrue();
    }

    @Test
    void matches_prometheusOnTheApplicationPort_isFalse() {
        assertThat(matcher.matches(request("/actuator/prometheus", 8085))).isFalse();
    }

    @Test
    void matches_otherActuatorPathOnTheManagementPort_isFalse() {
        assertThat(matcher.matches(request("/actuator/env", MANAGEMENT_PORT))).isFalse();
    }

    @Test
    void matches_whenNoManagementPortIsConfigured_isFalse() {
        RequestMatcher unconfigured = SecurityConfig.prometheusOnManagementPort(-1);

        assertThat(unconfigured.matches(request("/actuator/prometheus", -1))).isFalse();
        assertThat(unconfigured.matches(request("/actuator/prometheus", 0))).isFalse();
    }
}
