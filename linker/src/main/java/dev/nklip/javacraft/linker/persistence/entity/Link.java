package dev.nklip.javacraft.linker.persistence.entity;

import java.util.Date;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("link")
@Data
public class Link {

    @Id
    private String id;
    private String url;
    @Indexed(unique = true)
    private String shortUrl;
    private Date creationDate;
    private Date expirationDate;
    private long redirectCount;
    private Date lastAccessDate;

}
