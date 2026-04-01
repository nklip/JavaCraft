package dev.nklip.javacraft.linker.datamanager.dao;

import java.util.Optional;
import dev.nklip.javacraft.linker.datamanager.dao.entity.Link;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LinkRepository extends MongoRepository<Link, String> {

    Optional<Link> findByShortUrl(String shortUrl);

    boolean existsByShortUrl(String shortUrl);

    Optional<Link> findFirstByUrlOrderByCreationDateAsc(String url);

}
