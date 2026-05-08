package dev.feddi.api.usage;

import dev.feddi.api.usage.http.ReactiveHttpClient;
import dev.feddi.api.usage.http.ReactiveHttpRequest;
import dev.feddi.api.usage.http.ReactiveHttpResponse;
import dev.feddi.api.usage.v1.UsageReportRequest;
import dev.feddi.api.usage.v1.UsageReportResponse;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class ApiUsageReporterJcstressSupport {

    static final int REPORTS_PER_ACTOR = 1_000;
    static final ApiUsageInvocation INVOCATION = invocation();

    private ApiUsageReporterJcstressSupport() {
    }

    static ApiUsageReporter reporter(CountingReactiveHttpClient httpClient, int maxBatchSize, int maxQueueSize) {
        return ApiUsageReporter.builder(httpClient)
                .feddiGraphVariantKey("fddi_test_key")
                .autoStart(false)
                .flushInterval(Duration.ofDays(1))
                .maxBatchSize(maxBatchSize)
                .maxQueueSize(maxQueueSize)
                .randomSupplier(() -> 0.0)
                .scheduler(new NoopReporterScheduler())
                .build();
    }

    static void flush(ApiUsageReporter reporter) {
        reporter.flushNow().block(Duration.ofSeconds(5));
    }

    static void close(ApiUsageReporter reporter) {
        reporter.closeAsync().block(Duration.ofSeconds(5));
    }

    private static ApiUsageInvocation invocation() {
        return ApiUsageInvocation.builder()
                .document(Parser.parse("query GetUser { user { id name } }"))
                .operationName("GetUser")
                .schema(schema())
                .durationNanos(1_000)
                .clientName("jcstress")
                .clientVersion("1.0.0")
                .timestamp(Instant.parse("2026-05-08T00:00:00Z"))
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
                }
                """);
        return new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());
    }

    static final class CountingReactiveHttpClient implements ReactiveHttpClient {

        private final AtomicInteger acceptedCount = new AtomicInteger(0);

        @Override
        public Mono<ReactiveHttpResponse> exchange(ReactiveHttpRequest request) {
            return request.body()
                    .map(CountingReactiveHttpClient::parseUsageRequest)
                    .map(usageRequest -> {
                        int accepted = usageRequest.getRecordsCount();
                        acceptedCount.addAndGet(accepted);
                        return new ReactiveHttpResponse(
                                200,
                                Map.of(),
                                Mono.just(UsageReportResponse.newBuilder()
                                        .setAccepted(accepted)
                                        .build()
                                        .toByteArray())
                        );
                    });
        }

        int acceptedCount() {
            return acceptedCount.get();
        }

        private static UsageReportRequest parseUsageRequest(byte[] body) {
            try {
                return UsageReportRequest.parseFrom(body);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private static final class NoopReporterScheduler implements ReporterScheduler {

        @Override
        public void execute(Runnable task) {
        }

        @Override
        public Cancellable scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public void close() {
        }
    }
}
