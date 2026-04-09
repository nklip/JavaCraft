package dev.nklip.javacraft.openflights.app.service;

import dev.nklip.javacraft.openflights.app.model.SqlQueryResult;
import dev.nklip.javacraft.openflights.app.repository.SqlRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer for the admin SQL console.
 *
 * <p>The service exists to keep the controller thin and the repository focused
 * on IO. Its job is deliberately small but important:
 * <ul>
 *     <li>validate that the request contains executable SQL</li>
 *     <li>apply the default page and page-size values used by the UI</li>
 *     <li>enforce the allowed page-size contract shared with the frontend</li>
 *     <li>delegate execution to {@code SqlRepository}</li>
 * </ul>
 *
 * <p>That means the controller does not need to duplicate paging rules, and the
 * repository can assume it receives a normalized, valid execution request.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SqlService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(10, 20, 50, 100);

    private final SqlRepository sqlRepository;

    public SqlQueryResult executeSql(String sql, Integer page, Integer pageSize) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL query must not be blank.");
        }

        int effectivePage = page == null ? DEFAULT_PAGE : page;
        int effectivePageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;

        if (effectivePage < 1) {
            throw new IllegalArgumentException("Page number must be greater than 0.");
        }
        if (!ALLOWED_PAGE_SIZES.contains(effectivePageSize)) {
            throw new IllegalArgumentException("Page size must be one of: 10, 20, 50, 100.");
        }

        return sqlRepository.executeSql(sql, effectivePage, effectivePageSize);
    }
}
