package dev.feddi.api.usage;

import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiUsageDocumentAnalyzerTest {

    private static final GraphQLSchema SCHEMA = schema();

    private final ApiUsageDocumentAnalyzer analyzer = new ApiUsageDocumentAnalyzer();

    @Test
    void analyze_extractsScalarEnumObjectFieldsAndNamedFragments() {
        var usage = analyze("ViewerDashboard", """
                query ViewerDashboard($includeFriend: Boolean!) {
                  viewer {
                    id
                    name
                    status
                    profile {
                      bio
                      avatar {
                        url
                      }
                    }
                    ...FriendFields @include(if: $includeFriend)
                  }
                }

                fragment FriendFields on User {
                  friend {
                    id
                    name
                    status
                  }
                }
                """);

        assertThat(usage.operationName()).isEqualTo("ViewerDashboard");
        assertThat(usage.operationType()).isEqualTo("QUERY");
        assertThat(usage.canonicalDocument()).isNotBlank();
        assertThat(usage.fieldCoordinates()).containsExactly(
                "Query.viewer",
                "User.id",
                "User.name",
                "User.status",
                "User.profile",
                "Profile.bio",
                "Profile.avatar",
                "Image.url",
                "User.friend"
        );
    }

    @Test
    void analyze_extractsInlineFragmentsWithAndWithoutTypeConditions() {
        var usage = analyze("InlineFragments", """
                query InlineFragments {
                  viewer {
                    __typename
                    ... {
                      id
                      profile {
                        bio
                      }
                    }
                    ... on User {
                      friend {
                        name
                      }
                    }
                  }
                }
                """);

        assertThat(usage.fieldCoordinates()).containsExactly(
                "Query.viewer",
                "User.id",
                "User.profile",
                "Profile.bio",
                "User.friend",
                "User.name"
        );
    }

    @Test
    void analyze_extractsInterfaceAndUnionSelections() {
        var usage = analyze("InterfaceAndUnion", """
                query InterfaceAndUnion {
                  node(id: "1") {
                    __typename
                    ...NodeFields
                    ... on User {
                      name
                      status
                    }
                    ... on Product {
                      sku
                      price
                      owner {
                        id
                      }
                    }
                  }
                  search(text: "a") {
                    ...SearchResultFields
                  }
                }

                fragment NodeFields on Node {
                  id
                }

                fragment SearchResultFields on SearchResult {
                  ... on User {
                    friend {
                      name
                    }
                  }
                  ... on Product {
                    displayName
                  }
                }
                """);

        assertThat(usage.fieldCoordinates()).containsExactly(
                "Query.node",
                "Node.id",
                "User.name",
                "User.status",
                "Product.sku",
                "Product.price",
                "Product.owner",
                "User.id",
                "Query.search",
                "User.friend",
                "Product.displayName"
        );
    }

    @Test
    void analyze_breaksRecursiveFragmentCycles() {
        var usage = analyze("RecursiveFragments", """
                query RecursiveFragments {
                  viewer {
                    ...UserA
                  }
                }

                fragment UserA on User {
                  id
                  friend {
                    ...UserB
                  }
                }

                fragment UserB on User {
                  name
                  friend {
                    ...UserA
                  }
                }
                """);

        assertThat(usage.fieldCoordinates()).containsExactly(
                "Query.viewer",
                "User.id",
                "User.friend",
                "User.name"
        );
    }

    @Test
    void analyze_reportsRepeatedFieldSelectionsExactlyOnce() {
        var usage = analyze("DuplicateFields", """
                query DuplicateFields {
                  viewer {
                    id
                    id
                    userId: id
                    name
                    ... {
                      name
                      profile {
                        bio
                        bio
                      }
                    }
                    ... on User {
                      id
                      profile {
                        bio
                      }
                    }
                    ...RepeatedUserFields
                    ...RepeatedUserFields
                  }
                }

                fragment RepeatedUserFields on User {
                  id
                  name
                  profile {
                    bio
                  }
                }
                """);

        assertThat(usage.fieldCoordinates()).containsExactly(
                "Query.viewer",
                "User.id",
                "User.name",
                "User.profile",
                "Profile.bio"
        );
        assertThat(usage.fieldCoordinates()).doesNotHaveDuplicates();
    }

    @Test
    void analyze_reportsRepeatedFieldsAcrossInterfaceAndUnionSelectionsExactlyOnce() {
        var usage = analyze("DuplicateAbstractFields", """
                query DuplicateAbstractFields {
                  node(id: "1") {
                    ...NodeFields
                    ...NodeFields
                    ... on Node {
                      id
                    }
                    ... on User {
                      id
                      name
                      name
                    }
                  }
                  search(text: "a") {
                    ...SearchResultFields
                    ...SearchResultFields
                    ... on Product {
                      displayName
                      displayName
                    }
                  }
                }

                fragment NodeFields on Node {
                  id
                }

                fragment SearchResultFields on SearchResult {
                  ... on User {
                    id
                    name
                  }
                  ... on Product {
                    displayName
                  }
                }
                """);

        assertThat(usage.fieldCoordinates()).containsExactly(
                "Query.node",
                "Node.id",
                "User.id",
                "User.name",
                "Query.search",
                "Product.displayName"
        );
        assertThat(usage.fieldCoordinates()).doesNotHaveDuplicates();
    }

    @Test
    void analyze_usesSelectedOperationWhenDocumentContainsMultipleOperations() {
        var usage = analyze("ChangeStatus", """
                query Viewer {
                  viewer {
                    id
                  }
                }

                mutation ChangeStatus {
                  updateUserStatus(id: "1", status: ACTIVE) {
                    status
                    profile {
                      bio
                    }
                  }
                }
                """);

        assertThat(usage.operationName()).isEqualTo("ChangeStatus");
        assertThat(usage.operationType()).isEqualTo("MUTATION");
        assertThat(usage.fieldCoordinates()).containsExactly(
                "Mutation.updateUserStatus",
                "User.status",
                "User.profile",
                "Profile.bio"
        );
    }

    @Test
    void analyze_requiresOperationNameWhenDocumentContainsMultipleOperations() {
        assertThatThrownBy(() -> analyze(null, """
                query Viewer {
                  viewer {
                    id
                  }
                }

                query Search {
                  search(text: "a") {
                    ... on User {
                      id
                    }
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operationName is required when the document contains multiple operations");
    }

    private ApiUsageDocumentAnalyzer.ProcessedUsage analyze(@Nullable String operationName, String documentBody) {
        return analyzer.analyze(ApiUsageInvocation.builder()
                .document(Parser.parse(documentBody))
                .operationName(operationName)
                .schema(SCHEMA)
                .build());
    }

    private static GraphQLSchema schema() {
        var registry = new SchemaParser().parse("""
                interface Node {
                  id: ID!
                }

                interface Named {
                  displayName: String!
                }

                enum UserStatus {
                  ACTIVE
                  DISABLED
                }

                union SearchResult = User | Product

                type Query {
                  viewer: User!
                  node(id: ID!): Node
                  search(text: String): [SearchResult!]!
                }

                type Mutation {
                  updateUserStatus(id: ID!, status: UserStatus!): User
                }

                type Subscription {
                  userEvents: User
                }

                type User implements Node & Named {
                  id: ID!
                  displayName: String!
                  name: String!
                  status: UserStatus!
                  profile: Profile
                  friend: User
                  favoriteProduct: Product
                }

                type Product implements Node & Named {
                  id: ID!
                  displayName: String!
                  sku: String!
                  price: Int!
                  owner: User
                }

                type Profile {
                  bio: String!
                  avatar: Image
                }

                type Image {
                  url: String!
                }
                """);
        var wiring = RuntimeWiring.newRuntimeWiring()
                .type("Node", typeWiring -> typeWiring.typeResolver(environment -> null))
                .type("Named", typeWiring -> typeWiring.typeResolver(environment -> null))
                .type("SearchResult", typeWiring -> typeWiring.typeResolver(environment -> null))
                .build();
        return new SchemaGenerator().makeExecutableSchema(registry, wiring);
    }
}
