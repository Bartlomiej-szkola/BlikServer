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

import org.springframework.beans.factory.annotation.Value;

@Service
public class BlikService {

    private final BlikRepository repository;
    private final RestTemplate restTemplate;

    private final String bluebank_url;
    private final String redbank_url;

    public BlikService(BlikRepository repository, RestTemplate restTemplate, @Value("${bluebank.url}") String bluebank_url, @Value("${redbank.url}") String redbank_url) {
        this.repository = repository;
        this.restTemplate = restTemplate;
        this.bluebank_url = bluebank_url;
        this.redbank_url = redbank_url;
    }

    // --- NOWOŚĆ: ROUTING DO ODPOWIEDNIEGO BANKU ---
    private String getBankChargeUrl(String bankId) {
        switch (bankId.toUpperCase()) {
            case "BLUE_BANK":
                return bluebank_url; // Bank Niebieski
            case "RED_BANK":
                return redbank_url;
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

    // FUNKCJA DZIALA TYLKO DLA BLUEBANK!
    // FIXME Obsluga innych bankow!
    // HARDCODE DLA BLUEBANK
    public String transferToPhone(String fromAccount, String toPhone, BigDecimal amount) {
        try {
            // 1. PYTAMY BANK: "Kto ma taki numer telefonu?"
            String urlCheckPhone = bluebank_url + "/account/by-phone/" + toPhone;

            // Próba pobrania numeru konta
            ResponseEntity<String> response = restTemplate.getForEntity(urlCheckPhone, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String toAccount = response.getBody(); // Mamy numer konta odbiorcy!

                // 2. ZLECAMY PRZELEW: Wykorzystujemy istniejący endpoint /charge w Banku
                String description = "Przelew BLIK na telefon: " + toPhone;

                // Budujemy URL do obciążenia konta nadawcy
                String urlCharge = bluebank_url + "/charge" +
                        "?accountNumber=" + fromAccount +
                        "&amount=" + amount +
                        "&description=" + description;

                ResponseEntity<String> chargeResponse = restTemplate.postForEntity(urlCharge, null, String.class);

                if (chargeResponse.getStatusCode().is2xxSuccessful()) {
                    // SUKCES POBRANIA - TERAZ WPŁACAMY ODBIORCY:
                    String depositUrl = bluebank_url + "/deposit" +
                            "?accountNumber=" + toAccount +
                            "&amount=" + amount +
                            "&description=Przelew od telefonu: " + fromAccount; // Uproszczenie na potrzeby testów

                    restTemplate.postForEntity(depositUrl, null, String.class);

                    return "SUCCESS";
                } else {
                    return "BŁĄD: Bank odrzucił płatność brak - środków na koncie.";
                }
            } else {
                return "BŁĄD: Serwer banku zwrócił pustą odpowiedź.";
            }

        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // Obsługa błędu 404 - gdy numeru telefonu nie ma w bazie banku
            return "Odbiorca o numerze " + toPhone + " nie jest zarejestrowany w usłudze BLIK.";

        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            // Obsługa błędu 400 - np. błędy walidacji kwoty
            return "BŁĄD: Niepoprawne dane przelewu.";

        } catch (Exception e) {
            // Obsługa wszystkich innych błędów (np. serwer banku jest wyłączony)
            return "BŁĄD POŁĄCZENIA: " + e.getMessage();
        }
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
            // POBIERAMY ADRES ZALEŻNIE OD TEGO, JAKI BANK WYGENEROWAŁ KOD
            String bankBaseUrl = getBankChargeUrl(tx.getBankId());
            String description = "Zakupy w: " + tx.getStoreName();

            String url;
            if ("RED_BANK".equalsIgnoreCase(tx.getBankId())) {
                // Twój bank (Red Bank) używa 'storeName'
                url = bankBaseUrl + "/charge?accountNumber=" + tx.getAccountNumber() +
                        "&amount=" + tx.getAmount() +
                        "&storeName=" + tx.getStoreName();
            }
            else {
                url = bankBaseUrl + "/charge?accountNumber=" + tx.getAccountNumber() + "&amount=" + tx.getAmount() + "&description=" + description;
            }


            // Uderzamy do odpowiedniego banku!
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