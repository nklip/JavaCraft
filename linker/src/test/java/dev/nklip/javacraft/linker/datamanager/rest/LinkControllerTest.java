package dev.nklip.javacraft.linker.datamanager.rest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import dev.nklip.javacraft.linker.datamanager.dao.LinkRepository;
import dev.nklip.javacraft.linker.datamanager.dao.entity.Link;
import dev.nklip.javacraft.linker.datamanager.service.LinkServices;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class LinkControllerTest {

    LinkRepository linkRepository;
    LinkServices linkServices;
    LinkController linkController;

    @BeforeEach
    public void setUp() {
        linkRepository = Mockito.mock(LinkRepository.class);
        linkServices = Mockito.mock(LinkServices.class);
        linkController = new LinkController(linkRepository, linkServices);
    }

    @Test
    public void testFindAll() {
        List<Link> exptectedList = new ArrayList<>();
        Link link = new Link();
        link.setId("112");
        link.setUrl("long-url");
        link.setShortUrl("short-url");
        exptectedList.add(link);

        Mockito.when(linkRepository.findAll()).thenReturn(exptectedList);

        ResponseEntity<List<Link>> actualList = linkController.findAll();

        Assertions.assertNotNull(actualList);
        Assertions.assertNotNull(actualList.getBody());
        Assertions.assertEquals(1, actualList.getBody().size());
        Assertions.assertEquals("112", actualList.getBody().getFirst().getId());
    }

    @Test
    public void testShortUrl2FullUrl() {
        Link link = new Link();
        link.setId("112");
        link.setUrl("long-url");
        link.setShortUrl("short-url");

        Mockito.when(linkServices.resolveLink(Mockito.eq("short-url")))
                .thenReturn(new LinkServices.ResolveLinkResult(LinkServices.ResolveStatus.FOUND, link.getUrl()));

        ResponseEntity<byte[]> redirectResponse = linkController.shortUrl2FullUrl("short-url");

        Assertions.assertNotNull(redirectResponse);
        Assertions.assertNotNull(redirectResponse.getStatusCode());
        Assertions.assertEquals(302, redirectResponse.getStatusCode().value());
        Assertions.assertTrue(redirectResponse.getStatusCode().is3xxRedirection());

        Assertions.assertNotNull(redirectResponse.getHeaders());
        Assertions.assertEquals(1, redirectResponse.getHeaders().size());
        List<String> locationList = redirectResponse.getHeaders().get(HttpHeaders.LOCATION);
        Assertions.assertNotNull(locationList);
        Assertions.assertEquals(1, locationList.size());
        Assertions.assertEquals("long-url", locationList.getFirst());
    }

    @Test
    public void testShortUrlNotFound() {
        Mockito.when(linkServices.resolveLink(Mockito.eq("missing")))
                .thenReturn(new LinkServices.ResolveLinkResult(LinkServices.ResolveStatus.NOT_FOUND, null));

        ResponseEntity<byte[]> redirectResponse = linkController.shortUrl2FullUrl("missing");

        Assertions.assertNotNull(redirectResponse);
        Assertions.assertEquals(HttpStatus.NOT_FOUND.value(), redirectResponse.getStatusCode().value());
    }

    @Test
    public void testShortUrlExpired() {
        Mockito.when(linkServices.resolveLink(Mockito.eq("expired")))
                .thenReturn(new LinkServices.ResolveLinkResult(LinkServices.ResolveStatus.EXPIRED, null));

        ResponseEntity<byte[]> redirectResponse = linkController.shortUrl2FullUrl("expired");

        Assertions.assertNotNull(redirectResponse);
        Assertions.assertEquals(HttpStatus.GONE.value(), redirectResponse.getStatusCode().value());
    }

    @Test
    public void testAddLink() {
        Mockito.when(linkServices.createLink(Mockito.eq("long-url")))
                .thenReturn("http://localhost:8080/short-url");

        ResponseEntity<String> response = linkController.addLink("long-url");

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals("http://localhost:8080/short-url", response.getBody());
    }

    @Test
    public void testFindAnalytics() {
        LinkServices.LinkAnalytics analytics = new LinkServices.LinkAnalytics(
                "short-url",
                "long-url",
                new Date(),
                new Date(System.currentTimeMillis() + 1000),
                5L,
                new Date(),
                false
        );
        Mockito.when(linkServices.getAnalytics(Mockito.eq("short-url")))
                .thenReturn(java.util.Optional.of(analytics));

        ResponseEntity<LinkServices.LinkAnalytics> response = linkController.findAnalytics("short-url");

        Assertions.assertNotNull(response);
        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals("short-url", response.getBody().shortUrl());
        Assertions.assertEquals(5L, response.getBody().redirectCount());
    }

    @Test
    public void testFindAnalyticsShouldReturnNotFound() {
        Mockito.when(linkServices.getAnalytics(Mockito.eq("unknown")))
                .thenReturn(java.util.Optional.empty());

        ResponseEntity<LinkServices.LinkAnalytics> response = linkController.findAnalytics("unknown");

        Assertions.assertNotNull(response);
        Assertions.assertEquals(404, response.getStatusCode().value());
    }

}
