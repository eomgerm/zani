package com.a105.zani.contracts;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OpenApiContractSyntaxTest {

    private static final String SPEC_PATH = "src/main/resources/contracts/openapi/zani.yaml";

    @Test
    void zaniYamlIsAValidOpenApiDocument() {
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(SPEC_PATH, null, null);

        if (!result.getMessages().isEmpty()) {
            fail("OpenAPI 계약 파일에 파싱 오류가 있습니다: " + result.getMessages());
        }

        OpenAPI openApi = result.getOpenAPI();
        assertNotNull(openApi);
        assertTrue(openApi.getPaths().containsKey("/sessions"));
        assertTrue(openApi.getPaths().containsKey("/sessions/join"));
        assertNotNull(openApi.getPaths().get("/sessions").getPost());
        assertNotNull(openApi.getPaths().get("/sessions").getGet());
        assertNotNull(openApi.getPaths().get("/sessions/join").getPost());
    }
}
