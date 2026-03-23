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

    public BlikService(BlikRepository repository, RestTemplate restTemplate) {
        this.repository = repository;
        this.restTemplate = restTemplate;
    }

    // --- ROUTING: TWOJE IP DLA RED_BANK, LOCALHOST DLA KOLEGÓW ---
    private String getBankChargeUrl(String bankId) {
        switch (bankId.toUpperCase()) {
            case "BLUE_BANK":
                return "http://localhost:8081/api/bank/charge";
            case "RED_BANK":
                return "http://192.168.0.129:8083/api/bank/charge";
            default:
                throw new IllegalArgumentException("Nieobsługiwany bank: " + bankId);
        }
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
        transaction.setExpiresAt(LocalDateTime.now().plusMinutes(2));

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

        tx.setAmount(amount);
        tx.setStoreName(storeName);
        tx.setStatus(BlikStatus.PENDING_AUTHORIZATION);
        repository.save(tx);

        return "PENDING";
    }

    // 3. DLA APLIKACJI MOBILNEJ: Zatwierdź płatność
    public String authorizePayment(String accountNumber, boolean isApproved) {
        Optional<BlikTransaction> optTx = repository.findByAccountNumberAndStatus(accountNumber, BlikStatus.PENDING_AUTHORIZATION);
        if (optTx.isEmpty()) return "Brak transakcji";

        BlikTransaction tx = optTx.get();

        if (!isApproved) {
            tx.setStatus(BlikStatus.REJECTED);
            repository.save(tx);
            return "Odrzucono";
        }

        try {
            String bankBaseUrl = getBankChargeUrl(tx.getBankId());

            // --- BEZPIECZNA LOGIKA PARAMETRÓW ---
            String url;
            if ("RED_BANK".equalsIgnoreCase(tx.getBankId())) {
                // Twój bank (Red Bank) używa 'storeName'
                url = bankBaseUrl + "?accountNumber=" + tx.getAccountNumber() +
                        "&amount=" + tx.getAmount() +
                        "&storeName=" + tx.getStoreName();
            } else {
                // Bank kolegów (Blue Bank) używa 'description'
                String description = "Zakupy w: " + tx.getStoreName();
                url = bankBaseUrl + "?accountNumber=" + tx.getAccountNumber() +
                        "&amount=" + tx.getAmount() +
                        "&description=" + description;
            }

            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                tx.setStatus(BlikStatus.COMPLETED);
                repository.save(tx);
                return "Płatność zakończona sukcesem!";
            } else {
                tx.setStatus(BlikStatus.FAILED);
                repository.save(tx);
                return "Bank odrzucił (brak środków)";
            }
        } catch (Exception e) {
            tx.setStatus(BlikStatus.FAILED);
            repository.save(tx);
            return "Błąd banku: " + e.getMessage();
        }
    }

    // 4. METODY POMOCNICZE
    public Optional<BlikTransaction> getStatus(String code) {
        return repository.findByCodeAndStatus(code, BlikStatus.COMPLETED)
                .or(() -> repository.findByCodeAndStatus(code, BlikStatus.REJECTED))
                .or(() -> repository.findByCodeAndStatus(code, BlikStatus.FAILED))
                .or(() -> repository.findByCodeAndStatus(code, BlikStatus.PENDING_AUTHORIZATION));
    }

    public Optional<BlikTransaction> checkPending(String accountNumber) {
        return repository.findByAccountNumberAndStatus(accountNumber, BlikStatus.PENDING_AUTHORIZATION);
    }
}