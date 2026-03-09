package org.example.blikserver.service;

import org.example.blikserver.model.BlikStatus;
import org.example.blikserver.model.BlikTransaction;
import org.example.blikserver.repository.BlikRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class BlikService {

    private final BlikRepository repository;
    private final RestTemplate restTemplate;

    // Adres Banku Niebieskiego
    private final String BLUE_BANK_URL = "http://localhost:8081/api/bank/charge";

    public BlikService(BlikRepository repository, RestTemplate restTemplate) {
        this.repository = repository;
        this.restTemplate = restTemplate;
    }

    // 1. DLA APLIKACJI MOBILNEJ: Wygeneruj kod
    public String generateCode(String accountNumber, String bankId) {
        String code = String.format("%06d", new Random().nextInt(999999));

        BlikTransaction transaction = new BlikTransaction();
        transaction.setCode(code);
        transaction.setAccountNumber(accountNumber);
        transaction.setBankId(bankId);
        transaction.setStatus(BlikStatus.ACTIVE);
        transaction.setCreatedAt(LocalDateTime.now());
        transaction.setExpiresAt(LocalDateTime.now().plusMinutes(2)); // Ważny 2 minuty

        repository.save(transaction);
        return code;
    }

    // 2. DLA KASY W SKLEPIE: Inicjacja płatności
    public String initiatePayment(String code, BigDecimal amount, String storeName) {
        Optional<BlikTransaction> optTx = repository.findByCodeAndStatus(code, BlikStatus.ACTIVE);

        if (optTx.isEmpty()) return "BŁĄD: Nieważny lub nieistniejący kod BLIK";

        BlikTransaction tx = optTx.get();
        if (tx.getExpiresAt().isBefore(LocalDateTime.now())) {
            tx.setStatus(BlikStatus.EXPIRED);
            repository.save(tx);
            return "BŁĄD: Kod wygasł";
        }

        // Ustawiamy kwotę i czekamy na autoryzację na telefonie
        tx.setAmount(amount);
        tx.setStoreName(storeName);
        tx.setStatus(BlikStatus.PENDING_AUTHORIZATION);
        repository.save(tx);

        return "PENDING"; // Sygnał dla kasy, że ma czekać na klienta
    }

    // 3. DLA APLIKACJI MOBILNEJ: Zatwierdź płatność
    public String authorizePayment(String accountNumber, boolean isApproved) {
        Optional<BlikTransaction> optTx = repository.findByAccountNumberAndStatus(accountNumber, BlikStatus.PENDING_AUTHORIZATION);

        if (optTx.isEmpty()) return "Brak transakcji do zatwierdzenia";

        BlikTransaction tx = optTx.get();

        if (!isApproved) {
            tx.setStatus(BlikStatus.REJECTED);
            repository.save(tx);
            return "Odrzucono transakcję";
        }

        // KLIENT ZATWIERDZIŁ -> ŁĄCZYMY SIĘ Z BANKIEM NIEBIESKIM!
        try {
            String description = "Zakupy w: " + tx.getStoreName();
            String url = BLUE_BANK_URL + "?accountNumber=" + tx.getAccountNumber() + "&amount=" + tx.getAmount() + "&description=" + description;

            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                tx.setStatus(BlikStatus.COMPLETED);
                repository.save(tx);
                return "Płatność zakończona sukcesem!";
            } else {
                tx.setStatus(BlikStatus.FAILED);
                repository.save(tx);
                return "Bank odrzucił transakcję (brak środków?)";
            }
        } catch (Exception e) {
            tx.setStatus(BlikStatus.FAILED);
            repository.save(tx);
            return "Błąd komunikacji z bankiem: " + e.getMessage();
        }
    }

    // 4. METODY POMOCNICZE
    public Optional<BlikTransaction> getStatus(String code) {
        // Zwraca status dla kasy (Kasa będzie to odpytywać co sekundę)
        return repository.findByCodeAndStatus(code, BlikStatus.COMPLETED)
                .or(() -> repository.findByCodeAndStatus(code, BlikStatus.REJECTED))
                .or(() -> repository.findByCodeAndStatus(code, BlikStatus.FAILED))
                .or(() -> repository.findByCodeAndStatus(code, BlikStatus.PENDING_AUTHORIZATION));
    }

    public Optional<BlikTransaction> checkPending(String accountNumber) {
        // Zwraca oczekującą transakcję dla telefonu (aby wyświetlić ekran akceptacji)
        return repository.findByAccountNumberAndStatus(accountNumber, BlikStatus.PENDING_AUTHORIZATION);
    }
}