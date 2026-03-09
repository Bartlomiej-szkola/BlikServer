package org.example.blikserver.model;

public enum BlikStatus {
    ACTIVE,                 // Kod wygenerowany, ważny 2 minuty
    PENDING_AUTHORIZATION,  // Kasa w sklepie użyła kodu, czeka na PIN w telefonie
    COMPLETED,              // Pieniądze pobrane z banku, sukces
    REJECTED,               // Klient odrzucił w aplikacji
    EXPIRED,                // Minęły 2 minuty
    FAILED                  // Bank odrzucił (brak środków itp.)
}