package dev.nklip.javacraft.linker.datamanager.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import dev.nklip.javacraft.linker.datamanager.Application;
import dev.nklip.javacraft.linker.datamanager.dao.LinkRepository;
import dev.nklip.javacraft.linker.datamanager.dao.entity.Link;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class LinkControllerIntegrationTest {

    private static MongoServer mongoServer;
    private static String mongoUri;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository linkRepository;

    @BeforeAll
    static void setUpMongoServer() {
        mongoServer = new MongoServer(new MemoryBackend());
        InetSocketAddress serverAddress = mongoServer.bind();
        mongoUri = "mongodb://" + serverAddress.getHostString() + ":" + serverAddress.getPort() + "/linker_integration_test";
    }

    @AfterAll
    static void tearDownMongoServer() {
        if (mongoServer != null) {
            mongoServer.shutdownNow();
        }
    }

    @AfterEach
    void cleanup() {
        linkRepository.deleteAll();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongoUri);
        registry.add("spring.data.mongodb.database", () -> "linker_integration_test");
        registry.add("host", () -> "http://localhost:8080/api/v1/links");
        registry.add("linker.expiration-days", () -> 30L);
        registry.add("linker.short-url.length", () -> 6);
        registry.add("linker.short-url.max-attempts", () -> 64);
    }

    @Test
    void testShouldCreateRedirectAndReturnAnalytics() throws Exception {
        String targetUrl = "https://mail.google.com/mail/u/0/inbox";
        MvcResult createResult = mockMvc.perform(put("/api/v1/links")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(targetUrl))
                .andExpect(status().isOk())
                .andReturn();

        String fullShortUrl = createResult.getResponse().getContentAsString();
        Assertions.assertTrue(fullShortUrl.startsWith("http://localhost:8080/api/v1/links/"));

        String shortUrl = fullShortUrl.substring(fullShortUrl.lastIndexOf('/') + 1);

        mockMvc.perform(get("/api/v1/links/{shortUrl}", shortUrl))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", targetUrl));

        mockMvc.perform(get("/api/v1/links/{shortUrl}/analytics", shortUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl").value(shortUrl))
                .andExpect(jsonPath("$.url").value(targetUrl))
                .andExpect(jsonPath("$.redirectCount").value(1))
                .andExpect(jsonPath("$.expired").value(false));
    }

    @Test
    void testExpiredLinkShouldReturnGoneAndAnalyticsMarkedExpired() throws Exception {
        Link link = new Link();
        link.setUrl("https://expired.example.org");
        link.setShortUrl("gone01");
        link.setCreationDate(new Date());
        link.setExpirationDate(Date.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        linkRepository.save(link);

        mockMvc.perform(get("/api/v1/links/{shortUrl}", "gone01"))
                .andExpect(status().isGone());

        mockMvc.perform(get("/api/v1/links/{shortUrl}/analytics", "gone01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.redirectCount").value(0));
    }

    @Test
    void testUnknownLinkShouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/links/{shortUrl}", "missing"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/links/{shortUrl}/analytics", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testSameUrlShouldReturnExistingShortUrlInsteadOfCreatingDuplicate() throws Exception {
        String targetUrl = "https://repeat.example.org/item";

        MvcResult firstCreateResult = mockMvc.perform(put("/api/v1/links")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(targetUrl))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult secondCreateResult = mockMvc.perform(put("/api/v1/links")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(targetUrl))
                .andExpect(status().isOk())
                .andReturn();

        String firstShortUrl = firstCreateResult.getResponse().getContentAsString();
        String secondShortUrl = secondCreateResult.getResponse().getContentAsString();

        Assertions.assertEquals(firstShortUrl, secondShortUrl);
        long sameUrlCount = linkRepository.findAll()
                .stream()
                .filter(link -> targetUrl.equals(link.getUrl()))
                .count();
        Assertions.assertEquals(1L, sameUrlCount);
    }
}
