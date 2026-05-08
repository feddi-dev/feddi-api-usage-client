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
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnmodifiedType;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
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

        collectFields(rootType, operation.getSelectionSet(), coordinates, fragments, schema, new HashSet<>());
        return List.copyOf(coordinates);
    }

    private static void collectFields(
            GraphQLUnmodifiedType parentType,
            SelectionSet selectionSet,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema,
            Set<String> activeFragments
    ) {
        if (selectionSet == null) {
            return;
        }

        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                collectField(parentType, field, coordinates, fragments, schema, activeFragments);
            } else if (selection instanceof FragmentSpread spread) {
                collectFragment(spread, coordinates, fragments, schema, activeFragments);
            } else if (selection instanceof InlineFragment inlineFragment) {
                collectInlineFragment(parentType, inlineFragment, coordinates, fragments, schema, activeFragments);
            }
        }
    }

    private static void collectField(
            GraphQLUnmodifiedType parentType,
            Field field,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema,
            Set<String> activeFragments
    ) {
        String fieldName = field.getName();
        if (fieldName.startsWith("__")) {
            return;
        }

        if (!(parentType instanceof GraphQLFieldsContainer fieldsContainer)) {
            return;
        }

        GraphQLFieldDefinition fieldDefinition = fieldsContainer.getFieldDefinition(fieldName);
        if (fieldDefinition == null || field.getSelectionSet() == null) {
            if (fieldDefinition != null) {
                coordinates.add(fieldsContainer.getName() + "." + fieldName);
            }
            return;
        }

        coordinates.add(fieldsContainer.getName() + "." + fieldName);
        GraphQLUnmodifiedType unwrapped = GraphQLTypeUtil.unwrapAll(fieldDefinition.getType());
        if (isSelectableParentType(unwrapped)) {
            collectFields(unwrapped, field.getSelectionSet(), coordinates, fragments, schema, activeFragments);
        }
    }

    private static void collectFragment(
            FragmentSpread spread,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema,
            Set<String> activeFragments
    ) {
        String fragmentName = spread.getName();
        FragmentDefinition fragment = fragments.get(fragmentName);
        if (fragment == null) {
            return;
        }
        if (!activeFragments.add(fragmentName)) {
            return;
        }

        try {
            GraphQLType type = schema.getType(fragment.getTypeCondition().getName());
            if (type instanceof GraphQLUnmodifiedType unmodifiedType && isSelectableParentType(unmodifiedType)) {
                collectFields(unmodifiedType, fragment.getSelectionSet(), coordinates, fragments, schema, activeFragments);
            }
        } finally {
            activeFragments.remove(fragmentName);
        }
    }

    private static void collectInlineFragment(
            GraphQLUnmodifiedType parentType,
            InlineFragment inlineFragment,
            Set<String> coordinates,
            Map<String, FragmentDefinition> fragments,
            GraphQLSchema schema,
            Set<String> activeFragments
    ) {
        if (inlineFragment.getTypeCondition() == null) {
            collectFields(parentType, inlineFragment.getSelectionSet(), coordinates, fragments, schema, activeFragments);
            return;
        }

        GraphQLType type = schema.getType(inlineFragment.getTypeCondition().getName());
        if (type instanceof GraphQLUnmodifiedType unmodifiedType && isSelectableParentType(unmodifiedType)) {
            collectFields(unmodifiedType, inlineFragment.getSelectionSet(), coordinates, fragments, schema, activeFragments);
        }
    }

    private static boolean isSelectableParentType(GraphQLUnmodifiedType type) {
        return type instanceof GraphQLObjectType
                || type instanceof GraphQLInterfaceType
                || type instanceof GraphQLUnionType;
    }

    record ProcessedUsage(
            @Nullable String operationName,
            String operationType,
            String canonicalDocument,
            List<String> fieldCoordinates
    ) {
    }
}
