package com.ga.investmentservice.Service;

import com.ga.investmentservice.Enums.*;
import com.ga.investmentservice.Exception.*;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.WalletOperation;
import com.ga.investmentservice.Repository.PortfolioRepository;
import com.ga.investmentservice.Repository.WalletOperationRepository;
import com.ga.investmentservice.Security.CurrentUser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WalletPdfServiceTest {
    private PortfolioRepository portfolios;
    private WalletOperationRepository operations;
    private WalletPdfService service;
    private final LocalDate from = LocalDate.of(2026, 11, 1);
    private final LocalDate to = LocalDate.of(2026, 11, 30);

    @BeforeEach
    void setup() {
        portfolios = mock(PortfolioRepository.class);
        operations = mock(WalletOperationRepository.class);
        CurrentUser user = mock(CurrentUser.class);
        when(user.getUserId()).thenReturn(42L);
        Portfolio portfolio = new Portfolio();
        portfolio.setId(5L);
        portfolio.setCashBalance(new BigDecimal("999999.00"));
        when(portfolios.findByUserId(42L)).thenReturn(Optional.of(portfolio));
        service = new WalletPdfService(portfolios, operations, user);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPOSIT", "WITHDRAWAL"})
    void completedReceiptContainsRecordedDataAndNoProviderDetails(String type) throws Exception {
        WalletOperation operation = operation(1, type, "10.00");
        when(operations.findByReferenceAndPortfolio_Id(operation.getReference(), 5L)).thenReturn(Optional.of(operation));
        String text = text(service.getReceipt(operation.getReference()));
        assertTrue(text.contains("INVESTMENT PORTFOLIO"));
        assertTrue(text.contains("Transaction Receipt"));
        assertTrue(text.contains(type));
        assertTrue(text.contains("COMPLETED"));
        assertTrue(text.contains("USD 10.00"));
        assertTrue(text.contains(operation.getReference().toString()));
        assertTrue(text.contains("2026-11-12 12:30:00"));
        assertTrue(text.contains("USD 810.00"));
        assertTrue(text.contains(type.equals("WITHDRAWAL") ? "Simulated withdrawal" : "STRIPE SANDBOX"));
        assertTrue(text.contains("No real money"));
        assertFalse(text.contains("999,999"));
        assertFalse(text.contains("private-provider"));
        verify(operations, never()).save(any());
        verify(portfolios, never()).save(any());
        assertEquals(new BigDecimal("810.00"), operation.getBalanceAfter());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED", "CANCELLED"})
    void rejectsIncompleteReceipts(String status) {
        WalletOperation operation = operation(1, "DEPOSIT", "10.00");
        operation.setStatus(WalletOperationStatus.valueOf(status));
        when(operations.findByReferenceAndPortfolio_Id(operation.getReference(), 5L)).thenReturn(Optional.of(operation));
        assertThrows(BusinessRuleException.class, () -> service.getReceipt(operation.getReference()));
    }

    @Test
    void anotherOwnersReceiptIsNotFound() {
        UUID reference = UUID.randomUUID();
        when(operations.findByReferenceAndPortfolio_Id(reference, 5L)).thenReturn(Optional.empty());
        assertThrows(InformationNotFoundException.class, () -> service.getReceipt(reference));
        verify(operations).findByReferenceAndPortfolio_Id(reference, 5L);
        verifyNoMoreInteractions(operations);
    }

    @Test
    void statementUsesCompletedTimeBoundariesAndExactFundingTotals() throws Exception {
        WalletOperation deposit = operation(1, "DEPOSIT", "100.10");
        WalletOperation withdrawal = operation(2, "WITHDRAWAL", "20.05");
        deposit.setCreatedAt(from.minusMonths(1).atStartOfDay());
        deposit.setCompletedAt(from.atStartOfDay());
        withdrawal.setCompletedAt(to.atTime(23, 59, 59, 999999000));
        statementRows(List.of(deposit, withdrawal));
        String text = text(service.getStatement(from, to));
        assertTrue(text.contains("Wallet Statement"));
        assertTrue(text.contains("2026-11-01 to 2026-11-30 (inclusive)"));
        assertTrue(text.contains("Generated:"));
        assertTrue(text.contains("USD 100.10"));
        assertTrue(text.contains("USD 20.05"));
        assertTrue(text.contains("USD 80.05"));
        assertTrue(text.contains("Completed transactions: 2"));
        assertTrue(text.contains(deposit.getReference().toString()));
        assertTrue(text.contains(withdrawal.getReference().toString()));
        assertFalse(text.toLowerCase().contains("opening balance"));
        verify(operations).findByPortfolio_IdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAscIdAsc(
                5L, WalletOperationStatus.COMPLETED, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), PageRequest.of(0, 1001));
        verify(operations, never()).save(any());
        verify(portfolios, never()).save(any());
    }

    @Test
    void emptyStatementHasZeroTotalsAndClearExplanation() throws Exception {
        statementRows(List.of());
        String text = text(service.getStatement(from, to));
        assertTrue(text.contains("No completed wallet transactions"));
        assertTrue(text.contains("Completed transactions: 0"));
        assertTrue(text.contains("USD 0.00"));
    }

    @Test
    void validatesDateAndRecordLimitsWithoutTruncating() {
        assertThrows(BusinessRuleException.class, () -> service.getStatement(null, to));
        assertThrows(BusinessRuleException.class, () -> service.getStatement(to, from));
        assertThrows(BusinessRuleException.class, () -> service.getStatement(from, from.plusDays(366)));
        verifyNoInteractions(operations);
        statementRows(Collections.nCopies(1001, operation(1, "DEPOSIT", "10.00")));
        assertThrows(BusinessRuleException.class, () -> service.getStatement(from, to));
    }

    @Test
    void statementQueryScopesStatusDatesAndOrderingAndServiceIsReadOnly() {
        String method = "findByPortfolio_IdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAscIdAsc";
        var tree = new org.springframework.data.repository.query.parser.PartTree(method, WalletOperation.class);
        assertEquals(List.of("portfolio.id", "status", "completedAt", "completedAt"),
                tree.getParts().stream().map(part -> part.getProperty().toDotPath()).toList());
        assertEquals(List.of(org.springframework.data.repository.query.parser.Part.Type.SIMPLE_PROPERTY,
                        org.springframework.data.repository.query.parser.Part.Type.SIMPLE_PROPERTY,
                        org.springframework.data.repository.query.parser.Part.Type.GREATER_THAN_EQUAL,
                        org.springframework.data.repository.query.parser.Part.Type.LESS_THAN),
                tree.getParts().stream().map(org.springframework.data.repository.query.parser.Part::getType).toList());
        assertEquals(List.of("completedAt", "id"), tree.getSort().stream().map(org.springframework.data.domain.Sort.Order::getProperty).toList());
        assertTrue(tree.getSort().stream().allMatch(org.springframework.data.domain.Sort.Order::isAscending));
        assertTrue(WalletPdfService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
    }

    @Test
    void multiplePagesRepeatHeadersAndContainEveryReference() throws Exception {
        List<WalletOperation> rows = new ArrayList<>();
        for (int index = 1; index <= 40; index++) rows.add(operation(index, index % 2 == 0 ? "WITHDRAWAL" : "DEPOSIT", "10.00"));
        statementRows(rows);
        byte[] statement = service.getStatement(from, to);
        try (var document = Loader.loadPDF(statement)) {
            assertTrue(document.getNumberOfPages() > 1);
            PDFTextStripper stripper = new PDFTextStripper();
            String all = stripper.getText(document);
            for (WalletOperation row : rows) assertTrue(all.contains(row.getReference().toString()));
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String content = stripper.getText(document);
                assertTrue(content.contains("Transaction reference"));
                assertTrue(content.contains("Page " + page + " of " + document.getNumberOfPages()));
            }
            // synthetic previews stay in the ignored build directory
            Path directory = Path.of("target", "pdf-preview");
            Files.createDirectories(directory);
            Files.write(directory.resolve("statement.pdf"), statement);
            PDFRenderer renderer = new PDFRenderer(document);
            ImageIO.write(renderer.renderImageWithDPI(0, 110), "png", directory.resolve("statement-first.png").toFile());
            ImageIO.write(renderer.renderImageWithDPI(1, 110), "png", directory.resolve("statement-second.png").toFile());
            WalletOperation receipt = rows.get(0);
            when(operations.findByReferenceAndPortfolio_Id(receipt.getReference(), 5L)).thenReturn(Optional.of(receipt));
            byte[] bytes = service.getReceipt(receipt.getReference());
            Files.write(directory.resolve("receipt.pdf"), bytes);
            try (var receiptDocument = Loader.loadPDF(bytes)) {
                ImageIO.write(new PDFRenderer(receiptDocument).renderImageWithDPI(0, 110), "png", directory.resolve("receipt.png").toFile());
            }
        }
    }

    private void statementRows(List<WalletOperation> rows) {
        when(operations.findByPortfolio_IdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAscIdAsc(
                eq(5L), eq(WalletOperationStatus.COMPLETED), any(), any(), any())).thenReturn(rows);
    }

    private String text(byte[] bytes) throws Exception {
        assertEquals("%PDF-", new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII));
        try (var document = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(document); }
    }

    private WalletOperation operation(long id, String type, String amount) {
        WalletOperation operation = new WalletOperation();
        operation.setId(id);
        operation.setReference(new UUID(0, id));
        operation.setOperationType(WalletOperationType.valueOf(type));
        operation.setStatus(WalletOperationStatus.COMPLETED);
        operation.setAmount(new BigDecimal(amount));
        operation.setBalanceAfter(new BigDecimal("810.00"));
        operation.setCompletedAt(LocalDateTime.of(2026, 11, 12, 12, 30));
        operation.setPaymentMethod(type.equals("DEPOSIT") ? SimulatedPaymentMethod.STRIPE_SANDBOX : null);
        operation.setProviderPaymentId("private-provider");
        return operation;
    }
}
