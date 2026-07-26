package dev.nklip.javacraft.soap2rest.common.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    public static <T> T jsonToObject(String json, Class<T> className) {
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
