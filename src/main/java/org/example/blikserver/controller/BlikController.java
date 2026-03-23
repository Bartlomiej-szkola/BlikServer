package org.example.blikserver.controller;

import org.example.blikserver.model.BlikTransaction;
import org.example.blikserver.service.BlikService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/blik")
public class BlikController {

    private final BlikService blikService;

    public BlikController(BlikService blikService) {
        this.blikService = blikService;
    }

    // 1. Generuje kod (Wywoływane przez telefon)
    // POST /api/blik/generate?accountNumber=11112222&bankId=BLUE_BANK
    @PostMapping("/generate")
    public ResponseEntity<String> generateCode(@RequestParam String accountNumber, @RequestParam String bankId) {
        return ResponseEntity.ok(blikService.generateCode(accountNumber, bankId));
    }

    // 2. Sklep inicjuje płatność (Wywoływane przez Kasę WPF)
    // POST /api/blik/initiate?code=123456&amount=150.50&storeName=Biedronka
    @PostMapping("/initiate")
    public ResponseEntity<String> initiatePayment(@RequestParam String code, @RequestParam BigDecimal amount, @RequestParam String storeName) {
        return ResponseEntity.ok(blikService.initiatePayment(code, amount, storeName));
    }

    @PostMapping("/transfer")
    public ResponseEntity<String> transferToPhone(
            @RequestParam String fromAccount,
            @RequestParam String toPhone,
            @RequestParam BigDecimal amount) {
        // Wywołujemy logikę z serwisu
        String result = blikService.transferToPhone(fromAccount, toPhone, amount);
        return ResponseEntity.ok(result);
    }

    // 3. Telefon sprawdza, czy ktoś wpisał jego kod w kasie (Wywoływane przez telefon co 2 sekundy)
    @GetMapping("/pending/{accountNumber}")
    public ResponseEntity<BlikTransaction> checkPending(@PathVariable String accountNumber) {
        return blikService.checkPending(accountNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    // 4. Telefon zatwierdza lub odrzuca
    // POST /api/blik/authorize?accountNumber=11112222&isApproved=true
    @PostMapping("/authorize")
    public ResponseEntity<String> authorize(@RequestParam String accountNumber, @RequestParam boolean isApproved) {
        return ResponseEntity.ok(blikService.authorizePayment(accountNumber, isApproved));
    }

    // 5. Kasa sprawdza status transakcji (Wywoływane przez WPF co 2 sekundy)
    @GetMapping("/status/{code}")
    public ResponseEntity<String> getStatus(@PathVariable String code) {
        return blikService.getStatus(code)
                .map(tx -> ResponseEntity.ok(tx.getStatus().name()))
                .orElse(ResponseEntity.ok("NOT_FOUND"));
    }
}