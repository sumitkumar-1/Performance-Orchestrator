package com.example.perforchestrator.infrastructure.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Json {

  public static final JsonMapper MAPPER = JsonMapper.builder()
    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
    .build();

  private Json() {}

  public static String write(final Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode stored record", e);
    }
  }

  public static <T> T read(final String value, final Class<T> type) {
    try {
      return MAPPER.readValue(value, type);
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException("Cannot decode stored record", e);
    }
  }

  public static String hash(final String value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
