package dev.nklip.javacraft.linker.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.linker.persistence.repository.LinkRepository;
import dev.nklip.javacraft.linker.persistence.entity.Link;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Slf4j
@Setter
@Service
@RequiredArgsConstructor
public class LinkService {

    @Value("${host}")
    String host;

    @Value("${linker.short-url.length:6}")
    int shortUrlLength;

    @Value("${linker.short-url.max-attempts:64}")
    int maxShortUrlAttempts;

    @Value("${linker.expiration-days:30}")
    long expirationDays;

    final LinkRepository linkRepository;

    public String createLink(String url) {
        Optional<Link> existingLink = findExistingLinkByUrl(url);
        if (existingLink.isPresent()) {
            return fullShortUrl(existingLink.get().getShortUrl());
        }

        Date creationDate = new Date();
        Date expirationDate = Date.from(creationDate.toInstant().plus(expirationDays, ChronoUnit.DAYS));

        for (int attempt = 1; attempt <= maxShortUrlAttempts; attempt++) {
            String candidateShortUrl = generateCandidateShortUrl();
            if (linkRepository.existsByShortUrl(candidateShortUrl)) {
                continue;
            }

            Link link = new Link();
            link.setUrl(url);
            link.setShortUrl(candidateShortUrl);
            link.setCreationDate(creationDate);
            link.setExpirationDate(expirationDate);
            link.setRedirectCount(0L);

            try {
                Link saved = linkRepository.save(link);
                String fullShortUrl = fullShortUrl(saved.getShortUrl());
                log.info("Added a new Link with short url = '{}' and full short url = '{}'", saved.getShortUrl(), fullShortUrl);
                return fullShortUrl;
            } catch (DuplicateKeyException exception) {
                // Guard against races:
                // 1) same URL inserted concurrently in another request
                // 2) short-url collision in another request
                Optional<Link> concurrentExistingLink = findExistingLinkByUrl(url);
                if (concurrentExistingLink.isPresent()) {
                    return fullShortUrl(concurrentExistingLink.get().getShortUrl());
                }
                log.debug("Detected short-url collision for '{}', retrying...", candidateShortUrl);
            }
        }

        throw new IllegalStateException("Could not generate unique short url after " + maxShortUrlAttempts + " attempts");
    }

    public ResolveLinkResult resolveLink(String shortUrl) {
        Optional<Link> linkOptional = linkRepository.findByShortUrl(shortUrl);
        if (linkOptional.isEmpty()) {
            return new ResolveLinkResult(ResolveStatus.NOT_FOUND, null);
        }

        Link link = linkOptional.get();
        if (isExpired(link, Instant.now())) {
            return new ResolveLinkResult(ResolveStatus.EXPIRED, null);
        }

        link.setRedirectCount(link.getRedirectCount() + 1);
        link.setLastAccessDate(new Date());
        linkRepository.save(link);
        return new ResolveLinkResult(ResolveStatus.FOUND, link.getUrl());
    }

    public Optional<LinkAnalytics> getAnalytics(String shortUrl) {
        return linkRepository.findByShortUrl(shortUrl)
                .map(link -> new LinkAnalytics(
                        link.getShortUrl(),
                        link.getUrl(),
                        link.getCreationDate(),
                        link.getExpirationDate(),
                        link.getRedirectCount(),
                        link.getLastAccessDate(),
                        isExpired(link, Instant.now())
                ));
    }

    String generateCandidateShortUrl() {
        return SymbolGeneratorService.generateShortText(shortUrlLength);
    }

    private Optional<Link> findExistingLinkByUrl(String url) {
        return linkRepository.findFirstByUrlOrderByCreationDateAsc(url);
    }

    private String fullShortUrl(String shortUrl) {
        return hostPrefix() + shortUrl;
    }

    private String hostPrefix() {
        return host.endsWith("/") ? host : host + "/";
    }

    private boolean isExpired(Link link, Instant now) {
        Date expirationDate = link.getExpirationDate();
        return expirationDate != null && !now.isBefore(expirationDate.toInstant());
    }

    public enum ResolveStatus {
        FOUND,
        NOT_FOUND,
        EXPIRED
    }

    public record ResolveLinkResult(ResolveStatus status, String url) {
    }

    public record LinkAnalytics(
            String shortUrl,
            String url,
            Date creationDate,
            Date expirationDate,
            long redirectCount,
            Date lastAccessDate,
            boolean expired
    ) {
    }
}
