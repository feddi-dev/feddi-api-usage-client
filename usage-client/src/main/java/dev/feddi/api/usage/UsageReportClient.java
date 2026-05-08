package dev.feddi.api.usage;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.feddi.api.usage.http.ReactiveHttpClient;
import dev.feddi.api.usage.http.ReactiveHttpRequest;
import dev.feddi.api.usage.v1.UsageReportRequest;
import dev.feddi.api.usage.v1.UsageReportResponse;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class UsageReportClient {

    static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";
    static final String DEFAULT_USAGE_PROTO_PATH = "/api/usage-proto";
    static final URI DEFAULT_PLATFORM_HOST = URI.create("https://feddi.dev");

    private final ReactiveHttpClient httpClient;
    private final URI endpointUri;
    private final String bearerToken;

    UsageReportClient(ReactiveHttpClient httpClient, URI host, String bearerToken) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpointUri = resolveEndpoint(Objects.requireNonNull(host, "host"));
        this.bearerToken = requireNonBlank(bearerToken, "bearerToken");
    }

    Mono<UsageReportResponse> report(UsageReportRequest request) {
        Objects.requireNonNull(request, "request");

        var httpRequest = new ReactiveHttpRequest(
                "POST",
                endpointUri,
                Map.of(
                        "Authorization", List.of("Bearer " + bearerToken),
                        "Content-Type", List.of(PROTOBUF_CONTENT_TYPE),
                        "Accept", List.of(PROTOBUF_CONTENT_TYPE)
                ),
                Mono.just(request.toByteArray())
        );

        return httpClient.exchange(httpRequest)
                .switchIfEmpty(Mono.error(new UsageReportClientException("HTTP client completed without a response")))
                .flatMap(response -> response.body()
                        .defaultIfEmpty(new byte[0])
                        .flatMap(body -> parseResponse(response.statusCode(), body)));
    }

    private static Mono<UsageReportResponse> parseResponse(int statusCode, byte[] body) {
        if (statusCode < 200 || statusCode >= 300) {
            return Mono.error(new UsageReportClientException(
                    "Usage report request failed with HTTP status " + statusCode,
                    statusCode
            ));
        }

        try {
            return Mono.just(UsageReportResponse.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            return Mono.error(new UsageReportClientException("Usage report response was not valid protobuf", e));
        }
    }

    private static URI resolveEndpoint(URI baseUri) {
        if (!baseUri.isAbsolute()) {
            throw new IllegalArgumentException("host must be an absolute URI");
        }
        if (baseUri.getPath() != null && !baseUri.getPath().isBlank() && !baseUri.getPath().equals("/")) {
            throw new IllegalArgumentException("host must not include a path");
        }
        if (baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("host must not include a query or fragment");
        }
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + DEFAULT_USAGE_PROTO_PATH);
    }

    private static String requireNonBlank(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
