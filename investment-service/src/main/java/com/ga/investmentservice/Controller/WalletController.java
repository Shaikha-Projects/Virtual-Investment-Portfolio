package com.ga.investmentservice.Controller;

import com.ga.investmentservice.DTO.Request.DepositRequest;
import com.ga.investmentservice.DTO.Request.WithdrawalRequest;
import com.ga.investmentservice.DTO.Response.*;
import com.ga.investmentservice.Service.WalletService;
import com.ga.investmentservice.Service.WalletPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.time.LocalDate;

@RestController
@RequestMapping("/portfolio/wallet")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Tag(name = "Cash Wallet", description = "Sandbox deposits, simulated withdrawals, and wallet history")
@SecurityRequirement(name = "bearerAuth")
public class WalletController {
    private final WalletService walletService;
    private final WalletPdfService walletPdfService;

    @GetMapping("/operations/{reference}/receipt")
    @Operation(summary = "Download a completed wallet operation receipt")
    public ResponseEntity<byte[]> getReceipt(@PathVariable UUID reference) {
        return pdf(walletPdfService.getReceipt(reference), "wallet-receipt-" + reference + ".pdf");
    }

    @GetMapping("/statement")
    @Operation(summary = "Download a completed wallet operation statement")
    public ResponseEntity<byte[]> getStatement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pdf(walletPdfService.getStatement(from, to), "wallet-statement-" + from + "-to-" + to + ".pdf");
    }

    private ResponseEntity<byte[]> pdf(byte[] content, String filename) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(content);
    }

    @PostMapping("/deposits")
    @Operation(summary = "Create a pending Stripe deposit")
    public ResponseEntity<DepositResponse> createDeposit(@Valid @RequestBody DepositRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
        var result = walletService.createPendingDepositResult(request, idempotencyKey);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.response());
    }

    @PostMapping("/deposits/{reference}/verify")
    @Operation(summary = "Verify a Stripe deposit")
    public ResponseEntity<DepositResponse> verifyDeposit(@PathVariable UUID reference) {
        return ResponseEntity.ok(walletService.verifyPendingDeposit(reference));
    }

    @PostMapping("/deposits/{reference}/recover")
    @Operation(summary = "Recover an interrupted Stripe Checkout Session")
    public ResponseEntity<DepositResponse> recoverDeposit(@PathVariable UUID reference) {
        return ResponseEntity.ok(walletService.recoverPendingDeposit(reference));
    }

    @PostMapping("/withdrawals")
    @Operation(summary = "Withdraw simulated USD cash")
    public ResponseEntity<WithdrawalResponse> withdrawCash(@Valid @RequestBody WithdrawalRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
        var result = walletService.withdrawCashResult(request, idempotencyKey);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.response());
    }

    @GetMapping("/balance")
    @Operation(summary = "View wallet cash balances")
    public ResponseEntity<WalletBalanceResponse> getBalance() {
        return ResponseEntity.ok(walletService.getWalletBalance());
    }

    @GetMapping("/history")
    @Operation(summary = "View wallet operation history")
    public ResponseEntity<Page<WalletHistoryResponse>> getHistory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(walletService.getWalletHistory(page, size));
    }
}
