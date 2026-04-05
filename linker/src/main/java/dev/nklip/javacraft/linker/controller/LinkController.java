package dev.nklip.javacraft.linker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.linker.persistence.repository.LinkRepository;
import dev.nklip.javacraft.linker.persistence.entity.Link;
import dev.nklip.javacraft.linker.service.LinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/links")
@RequiredArgsConstructor
@Tag(name = "Linker", description = "Short-link management, redirect, and analytics API")
public class LinkController {

    private final LinkRepository linkRepository;
    private final LinkService linkService;

    @Operation(summary = "Find all stored links")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Link>> findAll() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(linkRepository.findAll());
    }

    @Operation(summary = "Redirect by short URL")
    @GetMapping(value = "/{shortUrl}")
    public ResponseEntity<byte []> shortUrl2FullUrl(@PathVariable("shortUrl") String shortUrl) {
        LinkService.ResolveLinkResult resolveLinkResult = linkService.resolveLink(shortUrl);
        switch (resolveLinkResult.status()) {
            case NOT_FOUND -> {
                return ResponseEntity.notFound().build();
            }
            case EXPIRED -> {
                return ResponseEntity.status(HttpStatus.GONE).build();
            }
            case null, default -> {}
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, resolveLinkResult.url());

        return new ResponseEntity<>(null, headers, HttpStatus.FOUND);
    }

    @Operation(summary = "Get analytics for a short URL")
    @GetMapping(value = "/{shortUrl}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkService.LinkAnalytics> findAnalytics(@PathVariable("shortUrl") String shortUrl) {
        return linkService.getAnalytics(shortUrl)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a short URL")
    @PutMapping(
            consumes = {
                    MediaType.TEXT_PLAIN_VALUE,
                    MediaType.APPLICATION_JSON_VALUE
            },
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<String> addLink(@RequestBody String url) {
        String normalizedUrl = normalizeUrlBody(url);
        return ResponseEntity
                .ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(linkService.createLink(normalizedUrl));
    }

    private String normalizeUrlBody(String url) {
        if (Objects.isNull(url) || url.length() < 2) {
            return url;
        }
        if (url.startsWith("\"") && url.endsWith("\"")) {
            return url.substring(1, url.length() - 1);
        }
        return url;
    }

}
