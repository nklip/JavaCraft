package my.javacraft.soap2rest.rest.app.dao;

import java.util.List;
import java.util.Optional;
import my.javacraft.soap2rest.rest.app.dao.entity.Meter;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface MeterDao extends JpaRepository<Meter, Long> {
    @Query(value =
            "SELECT m FROM Meter m WHERE m.accountId = :id")
    List<Meter> findByAccountId(@Param("id") Long id);

    Optional<Meter> findByIdAndAccountId(Long id, Long accountId);

    boolean existsByIdAndAccountId(Long id, Long accountId);

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM Meter m
            WHERE m.accountId = :id
            """)
    int deleteByAccountId(@Param("id") Long accountId);

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM Meter m
            WHERE m.id = :meterId AND m.accountId = :accountId
            """)
    int deleteByIdAndAccountId(@Param("meterId") Long meterId, @Param("accountId") Long accountId);
}
