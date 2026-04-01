package dev.nklip.javacraft.linker.datamanager.dao;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import dev.nklip.javacraft.linker.datamanager.dao.entity.Link;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataMongoTest
class LinkRepositoryTest {

    private static MongoServer mongoServer;
    private static String mongoUri;

    @Autowired
    private LinkRepository linkRepository;

    @BeforeAll
    static void setUpMongoServer() {
        mongoServer = new MongoServer(new MemoryBackend());
        InetSocketAddress serverAddress = mongoServer.bind();
        mongoUri = "mongodb://" + serverAddress.getHostString() + ":" + serverAddress.getPort() + "/linker_test";
    }

    @AfterAll
    static void tearDownMongoServer() {
        if (mongoServer != null) {
            mongoServer.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongoUri);
        registry.add("spring.data.mongodb.database", () -> "linker_test");
    }

    @Test
    void testShouldPersistAndFindByShortUrl() {
        linkRepository.deleteAll();

        Link link = new Link();
        link.setUrl("https://docs.example.org");
        link.setShortUrl("abc123");
        link.setCreationDate(new Date());
        link.setExpirationDate(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)));

        linkRepository.save(link);

        Optional<Link> foundLink = linkRepository.findByShortUrl("abc123");
        Assertions.assertTrue(foundLink.isPresent());
        Assertions.assertEquals("https://docs.example.org", foundLink.get().getUrl());
        Assertions.assertTrue(linkRepository.existsByShortUrl("abc123"));
    }

    @Test
    void testShouldPersistExpirationAndAnalyticsFields() {
        linkRepository.deleteAll();

        Date creationDate = new Date();
        Date expirationDate = Date.from(Instant.now().plus(10, ChronoUnit.DAYS));
        Date lastAccessDate = new Date();

        Link link = new Link();
        link.setUrl("https://analytics.example.org");
        link.setShortUrl("stats1");
        link.setCreationDate(creationDate);
        link.setExpirationDate(expirationDate);
        link.setRedirectCount(7);
        link.setLastAccessDate(lastAccessDate);

        linkRepository.save(link);

        Optional<Link> foundLink = linkRepository.findByShortUrl("stats1");
        Assertions.assertTrue(foundLink.isPresent());
        Assertions.assertEquals(expirationDate, foundLink.get().getExpirationDate());
        Assertions.assertEquals(7L, foundLink.get().getRedirectCount());
        Assertions.assertEquals(lastAccessDate, foundLink.get().getLastAccessDate());
    }

    @Test
    void testShouldFindExistingByUrl() {
        linkRepository.deleteAll();

        Link link = new Link();
        link.setUrl("https://repeat.example.org/path");
        link.setShortUrl("same11");
        link.setCreationDate(new Date());
        linkRepository.save(link);

        Optional<Link> foundLink = linkRepository.findFirstByUrlOrderByCreationDateAsc("https://repeat.example.org/path");
        Assertions.assertTrue(foundLink.isPresent());
        Assertions.assertEquals("same11", foundLink.get().getShortUrl());
    }
}
