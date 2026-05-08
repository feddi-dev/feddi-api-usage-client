package dev.feddi.api.usage;

import dev.feddi.api.usage.http.ReactiveHttpClient;
import dev.feddi.api.usage.http.ReactiveHttpRequest;
import dev.feddi.api.usage.http.ReactiveHttpResponse;
import dev.feddi.api.usage.v1.UsageReportRequest;
import dev.feddi.api.usage.v1.UsageReportResponse;
import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiUsageReporterTest {

    @Test
    void build_requiresFeddiGraphVariantKey() {
        ReactiveHttpClient httpClient = request -> Mono.error(new AssertionError("HTTP client should not be called"));

        var error = assertThrows(IllegalArgumentException.class, () -> ApiUsageReporter.builder(httpClient)
                .autoStart(false)
                .build());

        assertEquals("feddiGraphVariantKey must not be blank", error.getMessage());
    }

    @Test
    void builder_defaultsToFeddiDevUsageProtoEndpoint() throws Exception {
        var capturedRequests = new ArrayList<ReactiveHttpRequest>();
        ReactiveHttpClient httpClient = request -> {
            capturedRequests.add(request);
            return Mono.just(new ReactiveHttpResponse(
                    200,
                    Map.of(),
                    Mono.just(UsageReportResponse.newBuilder().setAccepted(1).build().toByteArray())
            ));
        };
        var reporter = ApiUsageReporter.builder(httpClient)
                .feddiGraphVariantKey("fddi_test_key")
                .autoStart(false)
                .build();

        reporter.report(invocation("query GetUser { user { id } }"));

        StepVerifier.create(reporter.flushNow())
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(URI.create("https://feddi.dev/api/usage-proto"), capturedRequests.getFirst().uri());
    }

    @Test
    void report_derivesCanonicalDocumentAndFieldCoordinates() throws Exception {
        var capturedRequests = new ArrayList<ReactiveHttpRequest>();
        var reporter = reporter(capturedRequests, 100, 1000);

        assertTrue(reporter.report(invocation("""
                query GetUser {
                  __typename
                  user {
                    id
                    ...UserFields
                    ... on User {
                      friend { name }
                    }
                  }
                }

                fragment UserFields on User {
                  name
                }
                """)));

        StepVerifier.create(reporter.flushNow())
                .assertNext(response -> assertEquals(1, response.getAccepted()))
                .verifyComplete();

        var request = parseOnlyUsageRequest(capturedRequests);
        var record = request.getRecords(0);
        assertEquals("GetUser", record.getOperationName());
        assertEquals("QUERY", record.getOperationType());
        assertFalse(record.getCanonicalDocument().isBlank());
        assertEquals(List.of("Query.user", "User.id", "User.name", "User.friend"), record.getFieldCoordinatesList());
        assertEquals(1_500_000, record.getDurationNanos());
        assertEquals("web-app", record.getClientName());
        assertEquals("1.2.0", record.getClientVersion());
        assertEquals(Instant.parse("2026-03-22T10:00:00Z").getEpochSecond(), record.getTimestamp().getSeconds());
    }

    @Test
    void flush_recalculatesSamplingAndCapturesMultiplierOnQueuedEvents() throws Exception {
        var capturedRequests = new ArrayList<ReactiveHttpRequest>();
        var reporter = reporter(capturedRequests, 1000, 2000);

        for (int i = 0; i < 150; i++) {
            reporter.report(invocation("query GetUser { user { id } }"));
        }

        StepVerifier.create(reporter.flushNow())
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(0.1, reporter.getSampleRate(), 0.0001);
        assertEquals(10, reporter.getMultiplier());

        reporter.report(invocation("query GetUser { user { name } }"));

        StepVerifier.create(reporter.flushNow())
                .expectNextCount(1)
                .verifyComplete();

        var request = parseUsageRequest(capturedRequests.getLast());
        assertEquals(1, request.getRecordsCount());
        assertTrue(request.getRecords(0).hasMultiplier());
        assertEquals(10, request.getRecords(0).getMultiplier());
    }

    @Test
    void report_dropsWhenQueueIsFull() {
        var capturedRequests = new ArrayList<ReactiveHttpRequest>();
        var reporter = reporter(capturedRequests, 100, 1);

        assertTrue(reporter.report(invocation("query GetUser { user { id } }")));
        assertFalse(reporter.report(invocation("query GetUser { user { name } }")));
        assertEquals(1, reporter.getDroppedCount());
        assertEquals(1, reporter.getPendingQueueSize());
    }

    @Test
    void closeAsync_flushesPendingUsage() throws Exception {
        var capturedRequests = new ArrayList<ReactiveHttpRequest>();
        var reporter = reporter(capturedRequests, 100, 1000);

        reporter.report(invocation("query GetUser { user { id } }"));

        StepVerifier.create(reporter.closeAsync())
                .assertNext(response -> assertEquals(1, response.getAccepted()))
                .verifyComplete();

        assertEquals(1, parseOnlyUsageRequest(capturedRequests).getRecordsCount());
        assertFalse(reporter.report(invocation("query GetUser { user { name } }")));
    }

    private static ApiUsageReporter reporter(
            List<ReactiveHttpRequest> capturedRequests,
            int maxBatchSize,
            int maxQueueSize
    ) {
        ReactiveHttpClient httpClient = request -> {
            capturedRequests.add(request);
            return Mono.just(new ReactiveHttpResponse(
                    200,
                    Map.of(),
                    Mono.just(UsageReportResponse.newBuilder().setAccepted(1).build().toByteArray())
            ));
        };
        return ApiUsageReporter.builder(httpClient)
                .host(URI.create("https://api.example.test"))
                .feddiGraphVariantKey("fddi_test_key")
                .autoStart(false)
                .flushInterval(Duration.ofSeconds(1))
                .maxBatchSize(maxBatchSize)
                .maxQueueSize(maxQueueSize)
                .randomSupplier(() -> 0.0)
                .build();
    }

    private static ApiUsageInvocation invocation(String documentBody) {
        Document document = Parser.parse(documentBody);
        return ApiUsageInvocation.builder()
                .document(document)
                .operationName("GetUser")
                .schema(schema())
                .durationNanos(1_500_000)
                .clientName("web-app")
                .clientVersion("1.2.0")
                .timestamp(Instant.parse("2026-03-22T10:00:00Z"))
                .build();
    }

    private static GraphQLSchema schema() {
        var registry = new SchemaParser().parse("""
                type Query {
                  user: User
                }

                type User {
                  id: ID!
                  name: String!
                  friend: User
                }
                """);
        return new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());
    }

    private static UsageReportRequest parseOnlyUsageRequest(List<ReactiveHttpRequest> capturedRequests) throws Exception {
        assertEquals(1, capturedRequests.size());
        return parseUsageRequest(capturedRequests.getFirst());
    }

    private static UsageReportRequest parseUsageRequest(ReactiveHttpRequest httpRequest) throws Exception {
        var body = httpRequest.body().block(Duration.ofSeconds(1));
        return UsageReportRequest.parseFrom(body);
    }
}
