package dev.nklip.javacraft.xsd2model.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provide services for serialization and deserialization from Json to Object and visa versa.
 * ObjectMapper is 100% thread safe.
 */
public class JsonService {

    private static final ObjectMapper mapper = createMapper();

    private static ObjectMapper createMapper() {
        return JsonMapper.builder()
                .changeDefaultVisibility(visibility -> visibility.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
                .changeDefaultPropertyInclusion(_ -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_EMPTY))
                .build();
    }

    public static String objectToJson(Object tag) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tag);
    }

    public static<T> T jsonToObject(String json, Class<T> className) {
        return mapper.readValue(json, className);
    }

    public static boolean isJson(String json) {
        try {
            if (json.contains("{") && json.contains("}")) {
                mapper.readTree(json);
                return true;
            } else {
                return false;
            }
        } catch (JacksonException e) {
            return false;
        }
    }
}
