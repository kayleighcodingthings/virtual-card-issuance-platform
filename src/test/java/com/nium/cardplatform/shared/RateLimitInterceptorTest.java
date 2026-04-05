package com.nium.cardplatform.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nium.cardplatform.shared.ratelimiting.RateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RateLimitInterceptor}.
 * <p>Uses a low {@code requestsPerMinute} (5) to make bucket exhaustion
 * testable without sending hundreds of requests. Each test gets a fresh
 * interceptor instance so bucket state does not leak between tests.
 */
@DisplayName("RateLimitInterceptorTest")
class RateLimitInterceptorTest {

    private static final int REQUESTS_PER_MINUTE = 5;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(REQUESTS_PER_MINUTE, new ObjectMapper());
        ReflectionTestUtils.setField(interceptor, "baseUri", "https://cardplatform.nium.com/errors/");
    }

    // --- Token Exhaustion ---
    @Nested
    @DisplayName("token exhaustion")
    class TokenExhaustion {

        @Test
        @DisplayName("allows requests up to the bucket capacity")
        void allowsUpToCapacity() throws Exception {
            for (int i = 0; i < REQUESTS_PER_MINUTE; i++) {
                MockHttpServletRequest request = newRequest("192.168.1.1");
                MockHttpServletResponse response = new MockHttpServletResponse();

                boolean allowed = interceptor.preHandle(request, response, null);

                assertThat(allowed)
                        .as("Request %d of %d should be allowed", i + 1, REQUESTS_PER_MINUTE)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("rejects request after bucket is exhausted")
        void rejectsAfterExhaustion() throws Exception {
            // Exhaust all tokens
            for (int i = 0; i < REQUESTS_PER_MINUTE; i++) {
                interceptor.preHandle(newRequest("192.168.1.1"), new MockHttpServletResponse(), null);
            }

            // Next request should be rejected
            MockHttpServletRequest request = newRequest("192.168.1.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean allowed = interceptor.preHandle(request, response, null);

            assertThat(allowed).isFalse();
            assertThat(response.getStatus()).isEqualTo(429);
        }

        @Test
        @DisplayName("returns Retry-After header when rate limited")
        void returnsRetryAfterHeader() throws Exception {
            exhaustBucket("10.0.0.1");

            MockHttpServletResponse response = new MockHttpServletResponse();
            interceptor.preHandle(newRequest("10.0.0.1"), response, null);

            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        }

        @Test
        @DisplayName("returns ProblemDetail JSON with correct error code")
        void returnsProblemDetailJson() throws Exception {
            exhaustBucket("10.0.0.2");

            MockHttpServletResponse response = new MockHttpServletResponse();
            interceptor.preHandle(newRequest("10.0.0.2"), response, null);

            assertThat(response.getContentType()).isEqualTo("application/problem+json");
            String body = response.getContentAsString();
            assertThat(body).contains("RATE_LIMIT_EXCEEDED");
            assertThat(body).contains("Rate limit exceeded");
        }
    }

    // --- Per-IP Isolation ---


    // --- Test Helpers ---
    private MockHttpServletRequest newRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cards");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private void exhaustBucket(String ip) throws Exception {
        for (int i = 0; i < REQUESTS_PER_MINUTE; i++) {
            interceptor.preHandle(newRequest(ip), new MockHttpServletResponse(), null);
        }
    }
}