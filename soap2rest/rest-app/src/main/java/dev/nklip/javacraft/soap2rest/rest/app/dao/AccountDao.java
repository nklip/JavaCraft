package dev.nklip.javacraft.soap2rest.rest.app.dao;

import dev.nklip.javacraft.soap2rest.rest.app.dao.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountDao extends JpaRepository<Account, Long> {

}
