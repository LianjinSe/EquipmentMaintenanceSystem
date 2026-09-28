package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.databind.JsonNode;

final class DemoException extends RuntimeException {
    final int status;
    final String code;
    final JsonNode details;

    DemoException(int status, String code, String message) {
        this(status, code, message, null);
    }

    DemoException(int status, String code, String message, JsonNode details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
