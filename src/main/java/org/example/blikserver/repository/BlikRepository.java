package org.example.blikserver.repository;

import org.example.blikserver.model.BlikStatus;
import org.example.blikserver.model.BlikTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BlikRepository extends JpaRepository<BlikTransaction, Long> {
    Optional<BlikTransaction> findByCodeAndStatus(String code, BlikStatus status);
    Optional<BlikTransaction> findByAccountNumberAndStatus(String accountNumber, BlikStatus status);
}