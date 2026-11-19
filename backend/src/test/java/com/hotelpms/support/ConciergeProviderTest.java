package com.hotelpms.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConciergeProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient transport = mock(HttpClient.class);

    @Test
    void toolRequestUsesConfiguredHttpsEndpointAndNeverAddsFaqAnswersOrSecretToJson() {
        var provider = provider("test-key-placeholder", "selected-test-model", "https://provider.invalid/custom/v1/");
        when(transport.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertThat(request.uri().toString()).isEqualTo("https://provider.invalid/custom/v1/chat/completions");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer test-key-placeholder");
            assertThat(request.timeout()).contains(Duration.ofSeconds(1));
            assertThat(request.method()).isEqualTo("POST");
            String body = requestBody(request);
            assertThat(body).doesNotContain("test-key-placeholder", "FAQ answer only kept in PMS", "customerId", "confirm_booking");
            var payload = json.readTree(body);
            assertThat(payload.path("model").asString()).isEqualTo("selected-test-model");
            assertThat(payload.path("parallel_tool_calls").asBoolean()).isFalse();
            assertThat(payload.path("max_completion_tokens").asInt()).isEqualTo(400);
            assertThat(payload.path("tools").values()).extracting(tool -> tool.path("function").path("name").asString())
                    .containsExactlyInAnyOrder("faq", "rooms", "catalog", "bookings", "clarify");
            var context = json.readTree(payload.path("messages").path(1).path("content").asString());
            assertThat(context.path("approvedFaqs").path(0).path("id").asLong()).isEqualTo(19);
            assertThat(context.path("previousCustomerMessages").path(0).asString()).isEqualTo("Previous public question");
            var callResult = Map.of("function", Map.of("name", "faq", "arguments", json.writeValueAsString(Map.of("id", 19))));
            return response(200, json.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "message", Map.of("tool_calls", List.of(callResult)))))));
        });
        var decision = provider.decide("When is check-in?", List.of("Previous public question"),
                List.of(new SupportTypes.Faq(19, "Public", "Check-in?", "FAQ answer only kept in PMS", true, Instant.EPOCH)),
                "2026-11-19");
        assertThat(decision.name()).isEqualTo("faq");
        assertThat(decision.arguments().path("id").asLong()).isEqualTo(19);
        verify(transport, times(1)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void missingConfigurationOrInsecureEndpointLeavesAdapterUnavailable() {
        for (var provider : List.of(provider("", "model", "https://provider.invalid"),
                provider("key", "", "https://provider.invalid"), provider("key", "model", "http://provider.invalid"))) {
            assertThat(provider.available()).isFalse();
            assertThatThrownBy(() -> provider.decide("Question", List.of(), List.of(), "2026-11-19"))
                    .isInstanceOf(ConciergeProvider.Unavailable.class);
        }
        verifyNoInteractions(transport);
    }

    @Test
    void timeoutCancelsOnceAndReturnsUnavailable() {
        var stalled = new CompletableFuture<HttpResponse<byte[]>>();
        when(transport.<byte[]>sendAsync(any(HttpRequest.class), any())).thenReturn(stalled);
        assertThatThrownBy(() -> provider("key", "model", "https://provider.invalid")
                .decide("Question", List.of(), List.of(), "2026-11-19")).isInstanceOf(ConciergeProvider.Unavailable.class);
        assertThat(stalled.isCancelled()).isTrue();
        verify(transport, times(1)).sendAsync(any(HttpRequest.class), any());
    }

    private ConciergeProvider provider(String key, String model, String url) {
        return new ConciergeProvider(json, true, "openai-compatible", key, model, url, Duration.ofSeconds(1), 400, 6, () -> transport);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<HttpResponse<byte[]>> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return CompletableFuture.completedFuture(response);
    }

    private static String requestBody(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable cause) { result.completeExceptionally(cause); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(1, TimeUnit.SECONDS);
    }
}
