package dev.nklip.javacraft.xsd2model.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * Provide services for serialization and deserialization from Json to Object and visa versa.
 * ObjectMapper is 100% thread safe.
 */
public class JsonService {

    private static final ObjectMapper mapper = createMapper();

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);
        return mapper;
    }

    public static String objectToJson(Object tag) throws JsonProcessingException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tag);
    }

    public static<T> T jsonToObject(String json, Class<T> className) throws IOException {
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
        } catch (IOException e) {
            return false;
        }
    }
}