package dev.nklip.javacraft.linker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import dev.nklip.javacraft.linker.dao.LinkRepository;
import dev.nklip.javacraft.linker.dao.entity.Link;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LinkServiceTest {

    private LinkRepository linkRepository;
    private LinkService linkService;

    @BeforeEach
    void setUp() {
        linkRepository = Mockito.mock(LinkRepository.class);
        linkService = Mockito.spy(new LinkService(linkRepository));
        linkService.setHost("http://localhost:8080/api/v1/links");
        linkService.setShortUrlLength(6);
        linkService.setMaxShortUrlAttempts(5);
        linkService.setExpirationDays(30);
    }

    @Test
    void testCreateLinkShouldRetryOnCollisionAndReturnShortUrl() {
        Mockito.when(linkRepository.findFirstByUrlOrderByCreationDateAsc("https://example.org/path"))
                .thenReturn(Optional.empty());
        Mockito.doReturn("ABC123", "XYZ789").when(linkService).generateCandidateShortUrl();
        Mockito.when(linkRepository.existsByShortUrl("ABC123")).thenReturn(true);
        Mockito.when(linkRepository.existsByShortUrl("XYZ789")).thenReturn(false);
        Mockito.when(linkRepository.save(Mockito.any(Link.class)))
                .thenAnswer(invocation -> {
                    Link link = invocation.getArgument(0);
                    link.setId("1");
                    return link;
                });

        String shortUrl = linkService.createLink("https://example.org/path");

        assertEquals("http://localhost:8080/api/v1/links/XYZ789", shortUrl);
        Mockito.verify(linkRepository, Mockito.times(1)).save(Mockito.any(Link.class));
    }

    @Test
    void testCreateLinkShouldReturnExistingWhenUrlAlreadyExists() {
        Link existingLink = new Link();
        existingLink.setShortUrl("ready11");
        existingLink.setUrl("https://existing.example/path");
        existingLink.setCreationDate(new Date());

        Mockito.when(linkRepository.findFirstByUrlOrderByCreationDateAsc("https://existing.example/path"))
                .thenReturn(Optional.of(existingLink));

        String shortUrl = linkService.createLink("https://existing.example/path");

        assertEquals("http://localhost:8080/api/v1/links/ready11", shortUrl);
        Mockito.verify(linkRepository, Mockito.never()).save(Mockito.any(Link.class));
        Mockito.verify(linkRepository, Mockito.never()).existsByShortUrl(Mockito.anyString());
    }

    @Test
    void testResolveLinkShouldReturnNotFoundWhenMissing() {
        Mockito.when(linkRepository.findByShortUrl("missing")).thenReturn(Optional.empty());

        LinkService.ResolveLinkResult result = linkService.resolveLink("missing");

        assertEquals(LinkService.ResolveStatus.NOT_FOUND, result.status());
        Mockito.verify(linkRepository, Mockito.never()).save(Mockito.any(Link.class));
    }

    @Test
    void testResolveLinkShouldReturnExpiredWhenLinkExpired() {
        Link link = new Link();
        link.setShortUrl("expired");
        link.setUrl("https://expired.example");
        link.setExpirationDate(Date.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        Mockito.when(linkRepository.findByShortUrl("expired")).thenReturn(Optional.of(link));

        LinkService.ResolveLinkResult result = linkService.resolveLink("expired");

        assertEquals(LinkService.ResolveStatus.EXPIRED, result.status());
        Mockito.verify(linkRepository, Mockito.never()).save(Mockito.any(Link.class));
    }

    @Test
    void testResolveLinkShouldIncrementAnalyticsAndReturnFound() {
        Link link = new Link();
        link.setShortUrl("ready");
        link.setUrl("https://ok.example");
        link.setExpirationDate(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        link.setRedirectCount(2);
        Mockito.when(linkRepository.findByShortUrl("ready")).thenReturn(Optional.of(link));
        Mockito.when(linkRepository.save(Mockito.any(Link.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LinkService.ResolveLinkResult result = linkService.resolveLink("ready");

        assertEquals(LinkService.ResolveStatus.FOUND, result.status());
        assertEquals("https://ok.example", result.url());
        assertEquals(3L, link.getRedirectCount());
        assertNotNull(link.getLastAccessDate());
        Mockito.verify(linkRepository, Mockito.times(1)).save(link);
    }

    @Test
    void testGetAnalyticsShouldReturnCurrentState() {
        Date now = new Date();
        Link link = new Link();
        link.setShortUrl("stat");
        link.setUrl("https://stats.example");
        link.setCreationDate(now);
        link.setExpirationDate(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        link.setRedirectCount(4);
        link.setLastAccessDate(now);
        Mockito.when(linkRepository.findByShortUrl("stat")).thenReturn(Optional.of(link));

        Optional<LinkService.LinkAnalytics> analyticsOptional = linkService.getAnalytics("stat");

        assertTrue(analyticsOptional.isPresent());
        LinkService.LinkAnalytics analytics = analyticsOptional.get();
        assertEquals("stat", analytics.shortUrl());
        assertEquals("https://stats.example", analytics.url());
        assertEquals(4L, analytics.redirectCount());
        assertEquals(now, analytics.lastAccessDate());
        assertFalse(analytics.expired());
    }
}
