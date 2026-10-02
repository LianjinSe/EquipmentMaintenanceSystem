package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ContractDocumentationTest {
    @Test
    void everyRegisteredEndpointIsDescribedAndUnimplementedEndpointsStayExplicit() throws Exception {
        JsonNode registry;
        try (InputStream input = getClass().getResourceAsStream("/capabilities.json")) {
            assertNotNull(input);
            registry = DemoDomain.JSON.readTree(input);
        }
        String documentation = Files.readString(Path.of("docs", "demo", "API.md"), StandardCharsets.UTF_8);
        Set<String> endpoints = new HashSet<>();
        for (JsonNode implemented : registry.path("implemented")) {
            String method = implemented.path("method").asText();
            String path = implemented.path("path").asText();
            assertTrue(endpoints.add(method + " " + path), "duplicate route: " + method + " " + path);
            assertTrue(documentation.contains("### " + method + " `" + path + "`"), "undocumented implemented route: " + path);
            assertTrue(implemented.path("implemented").asBoolean(), "implemented route marked reserved: " + path);
        }
        for (JsonNode future : registry.path("reserved")) {
            String method = future.path("method").asText();
            String path = future.path("path").asText();
            String id = future.path("id").asText();
            assertTrue(endpoints.add(method + " " + path), "duplicate route: " + method + " " + path);
            assertTrue(documentation.contains("### " + id + " · " + method + " `" + path + "`"), "undocumented reservation: " + id);
            assertTrue(documentation.contains("`501 NOT_IMPLEMENTED`"), "missing explicit 501 behavior");
            assertFalse(future.path("implemented").asBoolean(), "future route marked implemented: " + id);
            assertTrue(future.path("dependencies").size() > 0 && future.path("acceptance").size() > 0, "incomplete reservation: " + id);
        }
        assertEquals(77, registry.path("implemented").size());
        assertEquals(0, registry.path("reserved").size());
        int extensions = 0;
        for (JsonNode route : registry.path("implemented")) if (route.has("id")) extensions++;
        assertEquals(33, extensions);
    }

    @Test
    void sourceCoverageKeepsOneRowPerFunctionCard() throws Exception {
        String matrix = Files.readString(Path.of("docs", "demo", "功能覆盖矩阵.md"), StandardCharsets.UTF_8);
        long rows = matrix.lines().filter(line -> Pattern.matches("^\\| \\d{3} \\|.*", line)).count();
        assertEquals(103, rows);
        assertTrue(matrix.contains("`src/main/resources/capabilities.json`"));
    }
}
