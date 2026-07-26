package dev.nklip.javacraft.xsd2model.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xsd2model.model.ResponseType;
import org.xsd2model.model.UserType;

/**
 * Created by nikilipa on 8/20/16.
 */
public class JsonServiceTest {

    private UserType userType;

    @BeforeEach
    public void setUp() {
        UserType userType = new UserType();
        userType.setLogin("nikita");
        userType.setPassword("password22");
        this.userType = userType;
    }

    @Test
    public void testObject2Json2Object2Json() {
        // object
        ResponseType responseType0 = new ResponseType();
        responseType0.setUser(userType);
        responseType0.setCode("0");
        responseType0.setDesc("Ok!");

        // toJson
        String actualJson = JsonService.objectToJson(responseType0);
        // toObject
        ResponseType responseType2 = JsonService.jsonToObject(actualJson, ResponseType.class);
        // toJson
        String actualJson2 = JsonService.objectToJson(responseType2);

        Assertions.assertEquals(actualJson, actualJson2);
        // Jackson 3 enables MapperFeature.SORT_PROPERTIES_ALPHABETICALLY by default
        // (it was off in Jackson 2), so properties now serialize in alphabetical order
        // rather than declaration order. The content is unchanged.
        String expectedJson = """
                {
                  "code" : "0",
                  "desc" : "Ok!",
                  "user" : {
                    "login" : "nikita",
                    "password" : "password22"
                  }
                }""";
        Assertions.assertEquals(
                expectedJson.replaceAll("\r", "\n"),
                actualJson.replaceAll("\r\n", "\n")
        );
    }

    @Test
    void testIsJsonHandlesValidInvalidAndNonObjectInput() {
        Assertions.assertTrue(JsonService.isJson("{\"id\": 1}"));
        Assertions.assertFalse(JsonService.isJson("plain-text"));
        Assertions.assertFalse(JsonService.isJson("{not-valid-json}"));
    }

}
