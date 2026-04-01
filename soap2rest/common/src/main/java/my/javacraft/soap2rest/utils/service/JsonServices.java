package my.javacraft.soap2rest.utils.service;

import java.io.IOException;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;

public class JsonServices {

    private static final ObjectMapper mapper = ((Supplier<ObjectMapper>) () -> {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);
        return mapper;
    }).get();

    public static String objectToJson(Object tag) throws JsonProcessingException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tag);
    }

    public static <T> T jsonToObject(String json, Class<T> className) throws IOException {
        return mapper.readValue(json, className);
    }

    public static boolean isJson(String json) {
        try {
            if (json.contains("{") && json.contains("}")) {
                final ObjectMapper mapper = new ObjectMapper();
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