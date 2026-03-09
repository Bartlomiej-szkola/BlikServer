package org.example.blikserver.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
public class BlikTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code; // 6-cyfrowy kod
    private String accountNumber; // Konto, z którego zejdą środki
    private String bankId; // np. "BLUE_BANK" lub "PINK_BANK"

    private BigDecimal amount;
    private String storeName;

    @Enumerated(EnumType.STRING)
    private BlikStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}