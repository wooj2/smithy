/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.aws.apigateway.openapi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import software.amazon.smithy.aws.apigateway.traits.IntegrationTrait;
import software.amazon.smithy.aws.apigateway.traits.IntegrationTraitIndex;
import software.amazon.smithy.aws.apigateway.traits.MockIntegrationTrait;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.traits.CorsTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.openapi.OpenApiException;
import software.amazon.smithy.openapi.fromsmithy.Context;
import software.amazon.smithy.openapi.model.OperationObject;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.Pair;

/**
 * Adds API Gateway integrations to operations.
 *
 * <p>If the service has the {@link CorsTrait}, then integration responses
 * will include a statically computed Access-Control-Expose-Headers
 * CORS headers that contains every header exposed by the integration,
 * and Access-Control-Allow-Credentials header if the operation uses
 * a security scheme that needs it, and and Access-Control-Allow-Origin
 * header that is the result of {@link CorsTrait#getOrigin()}.
 */
final class AddIntegrations implements ApiGatewayMapper {

    private static final Logger LOGGER = Logger.getLogger(AddIntegrations.class.getName());
    private static final String EXTENSION_NAME = "x-amazon-apigateway-integration";
    private static final String RESPONSES_KEY = "responses";
    private static final String HEADER_PREFIX = "method.response.header.";
    private static final String DEFAULT_KEY = "default";
    private static final String STATUS_CODE_KEY = "statusCode";
    private static final String RESPONSE_PARAMETERS_KEY = "responseParameters";
    private static final String PASSTHROUGH_BEHAVIOR = "passthroughBehavior";
    private static final String INCORRECT_PASSTHROUGH_BEHAVIOR = "passThroughBehavior";

    @Override
    public List<ApiGatewayConfig.ApiType> getApiTypes() {
        return ListUtils.of(ApiGatewayConfig.ApiType.REST, ApiGatewayConfig.ApiType.HTTP);
    }

    @Override
    public OperationObject updateOperation(
            Context<? extends Trait> context,
            OperationShape shape,
            OperationObject operation,
            String httpMethod,
            String path
    ) {
        IntegrationTraitIndex index = IntegrationTraitIndex.of(context.getModel());
        return index.getIntegrationTrait(context.getService(), shape)
                .map(trait -> operation.toBuilder()
                        .putExtension(EXTENSION_NAME, createIntegration(context, operation, shape, trait))
                        .build())
                .orElseGet(() -> {
                    LOGGER.warning("No API Gateway integration trait found for " + shape.getId());
                    return operation;
                });
    }

    private ObjectNode createIntegration(
            Context<? extends Trait> context,
            OperationObject operationObject,
            OperationShape shape,
            Trait integration
    ) {
        ObjectNode integrationObject = getIntegrationAsObject(context, shape, integration);
        return context.getService().getTrait(CorsTrait.class)
                .map(cors -> {
                    LOGGER.fine(() -> String.format("Adding CORS to `%s` operation responses", shape.getId()));
                    return updateIntegrationWithCors(context, operationObject, shape, integrationObject, cors);
                })
                .orElse(integrationObject);
    }

    private static ObjectNode getIntegrationAsObject(
            Context<? extends Trait> context,
            OperationShape shape,
            Trait integration
    ) {
        ObjectNode integrationNode;
        if (integration instanceof MockIntegrationTrait) {
            integrationNode = integration.toNode().expectObjectNode().withMember("type", Node.from("mock"));
        } else if (integration instanceof IntegrationTrait) {
            validateTraitConfiguration((IntegrationTrait) integration, context, shape);
            integrationNode = ((IntegrationTrait) integration).toExpandedNode(context.getService(), shape);
        } else {
            throw new OpenApiException("Unexpected integration trait: " + integration);
        }

        // Fix a naming issue where the Smithy trait uses a different casing for "passthroughBehavior"
        Optional<Node> passthroughBehavior = integrationNode.getMember(INCORRECT_PASSTHROUGH_BEHAVIOR);
        if (passthroughBehavior.isPresent()) {
            integrationNode = integrationNode
                    .withoutMember(INCORRECT_PASSTHROUGH_BEHAVIOR)
                    .withMember(PASSTHROUGH_BEHAVIOR, passthroughBehavior.get());
        }
        return integrationNode;
    }

    private static void validateTraitConfiguration(IntegrationTrait trait,
                                                   Context<? extends Trait> context,
                                                   OperationShape operation) {
        // For HTTP APIs, API Gateway requires that the payloadFormatVersion is set on integrations.
        // https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-swagger-extensions-integration.html
        // If the payloadFormatVersion has not been set on an integration and the apiGatewayType has been set to "HTTP",
        // the conversion fails.
        ApiGatewayConfig.ApiType apiType = context.getConfig().getExtensions(ApiGatewayConfig.class)
                .getApiGatewayType();
        if (!trait.getPayloadFormatVersion().isPresent() && apiType.equals(ApiGatewayConfig.ApiType.HTTP)) {
            throw new OpenApiException("When the 'apiGatewayType' OpenAPI conversion setting is 'HTTP', a "
                    + "'payloadFormatVersion' must be set on the aws.apigateway#integration trait.");
        }
    }

    private ObjectNode updateIntegrationWithCors(
            Context<? extends Trait> context,
            OperationObject operationObject,
            OperationShape shape,
            ObjectNode integrationNode,
            CorsTrait cors
    ) {
        ObjectNode responses = integrationNode.getObjectMember(RESPONSES_KEY).orElse(Node.objectNode());

        // Always include a "default" response that has the same HTTP response code.
        if (!responses.getMember(DEFAULT_KEY).isPresent()) {
            responses = responses.withMember(DEFAULT_KEY, Node.objectNode().withMember(STATUS_CODE_KEY, "200"));
        }

        Map<CorsHeader, String> corsHeaders = new HashMap<>();
        corsHeaders.put(CorsHeader.ALLOW_ORIGIN, cors.getOrigin());
        if (context.usesHttpCredentials()) {
            corsHeaders.put(CorsHeader.ALLOW_CREDENTIALS, "true");
        }

        LOGGER.finer(() -> String.format("Adding the following CORS headers to the API Gateway integration of %s: %s",
                                         shape.getId(), corsHeaders));
        Set<String> deducedHeaders = CorsHeader.deduceOperationResponseHeaders(context, operationObject, shape, cors);
        LOGGER.fine(() -> String.format("Detected the following headers for operation %s: %s",
                                        shape.getId(), deducedHeaders));

        // Update each response by adding CORS headers.
        responses = responses.getMembers().entrySet().stream()
                .peek(entry -> LOGGER.fine(() -> String.format(
                        "Updating integration response %s for `%s` with CORS", entry.getKey(), shape.getId())))
                .map(entry -> Pair.of(entry.getKey(), updateIntegrationResponse(
                        shape, corsHeaders, deducedHeaders, entry.getValue().expectObjectNode())))
                .collect(ObjectNode.collect(Pair::getLeft, Pair::getRight));

        return integrationNode.withMember(RESPONSES_KEY, responses);
    }

    private ObjectNode updateIntegrationResponse(
            OperationShape shape,
            Map<CorsHeader, String> corsHeaders,
            Set<String> deduced,
            ObjectNode response
    ) {
        Map<CorsHeader, String> responseHeaders = new HashMap<>(corsHeaders);
        ObjectNode responseParams = response.getObjectMember(RESPONSE_PARAMETERS_KEY).orElseGet(Node::objectNode);

        // Created a sorted set of all headers exposed in the integration.
        Set<String> headersToExpose = new TreeSet<>(deduced);
        responseParams.getStringMap().keySet().stream()
                .filter(parameterName -> parameterName.startsWith(HEADER_PREFIX))
                .map(parameterName -> parameterName.substring(HEADER_PREFIX.length()))
                .forEach(headersToExpose::add);
        String headersToExposeString = String.join(",", headersToExpose);

        // If there are exposed headers, then add a new header to the integration
        // that lists all of them. See https://fetch.spec.whatwg.org/#http-access-control-expose-headers.
        if (!headersToExposeString.isEmpty()) {
            responseHeaders.put(CorsHeader.EXPOSE_HEADERS, headersToExposeString);
            LOGGER.fine(() -> String.format("Adding `%s` header to `%s` with value of `%s`",
                                            CorsHeader.EXPOSE_HEADERS, shape.getId(), headersToExposeString));
        }

        if (responseHeaders.isEmpty()) {
            LOGGER.fine(() -> "No headers are exposed by " + shape.getId());
            return response;
        }

        // Create an updated response that injects Access-Control-Expose-Headers.
        ObjectNode.Builder builder = responseParams.toBuilder();
        for (Map.Entry<CorsHeader, String> entry : responseHeaders.entrySet()) {
            builder.withMember(HEADER_PREFIX + entry.getKey(), "'" + entry.getValue() + "'");
        }

        return response.withMember(RESPONSE_PARAMETERS_KEY, builder.build());
    }
}
