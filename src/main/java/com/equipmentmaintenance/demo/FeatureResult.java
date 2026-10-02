package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.databind.JsonNode;

record FeatureResult(int status, JsonNode data) {
  static FeatureResult ok(JsonNode data) {
    return new FeatureResult(200, data);
  }
}
