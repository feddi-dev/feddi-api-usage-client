package dev.feddi.api.usage;

import graphql.language.AstPrinter;
import graphql.language.AstSignature;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnmodifiedType;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ApiUsageDocumentAnalyzer {

    ProcessedUsage analyze(ApiUsageInvocation invocation) {
        var operation = findOperation(invocation.document(), invocation.operationName());
        var normalized = new AstSignature().signatureQuery(invocation.document(), invocation.operationName());
        var canonicalDocument = AstPrinter.printAstCompact(normalized);
        var fieldCoordinates = extractFieldCoordinates(
                operation,
                invocation.document(),
                invocation.schema()
        );

        return new ProcessedUsage(
                operation.getName(),
                operation.getOperation().name(),
                canonicalDocument,
                fieldCoordinates
        );
    }

    private static OperationDefinition findOperation(Document document, @Nullable String operationName) {
        var operations = document.getDefinitionsOfType(OperationDefinition.class);
        if (operationName != null && !operationName.isBlank()) {
            return operations.stream()
                    .filter(operation -> operationName.equals(operation.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("GraphQL operation not found: " + operationName));
        }
        if (operations.size() == 1) {
            return operations.getFirst();
        }
        throw new IllegalArgumentException("operationName is required when the document contains multiple operations");
    }

    private static List<String> extractFieldCoordinates(
            OperationDefinition operation,
            Document document,
            GraphQLSchema schema
    ) {
        Set<String> coordinates = new LinkedHashSet<>();
        var fragments = document.getDefinitionsOfType(FragmentDefinition.class).stream()
                .collect(Collectors.toMap(FragmentDefinition::getName, fragment -> fragment));

        String rootTypeName = switch (operation.getOperation()) {
            case QUERY -> "Query";
            case MUTATION -> "Mutation";
            case SUBSCRIPTION -> "Subscription";
        };

        GraphQLObjectType rootType = schema.getObjectType(rootTypeName);
        if (rootType == null) {
            return List.of();
        }

        collectFields(rootType, operation.getSelectionSet(), coordinates, fragments, schema);
        return List.copyOf(coordinates);
    }

    private static void collectFields(
            GraphQLObjectType parentType,
            SelectionSet selectionSet,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema
    ) {
        if (selectionSet == null) {
            return;
        }

        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                collectField(parentType, field, coordinates, fragments, schema);
            } else if (selection instanceof FragmentSpread spread) {
                collectFragment(spread, coordinates, fragments, schema);
            } else if (selection instanceof InlineFragment inlineFragment) {
                collectInlineFragment(parentType, inlineFragment, coordinates, fragments, schema);
            }
        }
    }

    private static void collectField(
            GraphQLObjectType parentType,
            Field field,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema
    ) {
        String fieldName = field.getName();
        if (fieldName.startsWith("__")) {
            return;
        }

        coordinates.add(parentType.getName() + "." + fieldName);
        GraphQLFieldDefinition fieldDefinition = parentType.getFieldDefinition(fieldName);
        if (fieldDefinition == null || field.getSelectionSet() == null) {
            return;
        }

        GraphQLUnmodifiedType unwrapped = GraphQLTypeUtil.unwrapAll(fieldDefinition.getType());
        if (unwrapped instanceof GraphQLObjectType objectType) {
            collectFields(objectType, field.getSelectionSet(), coordinates, fragments, schema);
        }
    }

    private static void collectFragment(
            FragmentSpread spread,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema
    ) {
        FragmentDefinition fragment = fragments.get(spread.getName());
        if (fragment == null) {
            return;
        }

        GraphQLType type = schema.getType(fragment.getTypeCondition().getName());
        if (type instanceof GraphQLObjectType objectType) {
            collectFields(objectType, fragment.getSelectionSet(), coordinates, fragments, schema);
        }
    }

    private static void collectInlineFragment(
            GraphQLObjectType parentType,
            InlineFragment inlineFragment,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema
    ) {
        if (inlineFragment.getTypeCondition() == null) {
            collectFields(parentType, inlineFragment.getSelectionSet(), coordinates, fragments, schema);
            return;
        }

        GraphQLType type = schema.getType(inlineFragment.getTypeCondition().getName());
        if (type instanceof GraphQLObjectType objectType) {
            collectFields(objectType, inlineFragment.getSelectionSet(), coordinates, fragments, schema);
        }
    }

    record ProcessedUsage(
            @Nullable String operationName,
            String operationType,
            String canonicalDocument,
            List<String> fieldCoordinates
    ) {
    }
}
