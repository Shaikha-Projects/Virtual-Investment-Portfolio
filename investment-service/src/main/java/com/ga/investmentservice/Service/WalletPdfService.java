package com.ga.investmentservice.Service;

import com.ga.investmentservice.Enums.WalletOperationStatus;
import com.ga.investmentservice.Enums.WalletOperationType;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.WalletOperation;
import com.ga.investmentservice.Repository.PortfolioRepository;
import com.ga.investmentservice.Repository.WalletOperationRepository;
import com.ga.investmentservice.Security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true)
public class WalletPdfService {
    private final PortfolioRepository portfolioRepository;
    private final WalletOperationRepository walletOperationRepository;
    private final CurrentUser currentUser;

    public byte[] getReceipt(UUID reference) {
        Long portfolioId = portfolioRepository.findByUserId(currentUser.getUserId())
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist")).getId();
        WalletOperation operation = walletOperationRepository.findByReferenceAndPortfolio_Id(reference, portfolioId)
                .orElseThrow(() -> new InformationNotFoundException("Wallet operation does not exist"));
        if (operation.getStatus() != WalletOperationStatus.COMPLETED) {
            throw new BusinessRuleException("Receipts are available only for completed wallet operations");
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            document.getDocumentInformation().setTitle("Cash Wallet Transaction Receipt");
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                heading(stream, regular, bold, "Transaction Receipt");
                box(stream, 48, 610, 499, 80, new Color(237, 244, 252));
                text(stream, bold, 11, 62, 668, operation.getOperationType().name(), new Color(37, 99, 180));
                text(stream, bold, 26, 62, 630, money(operation.getAmount()), navy());
                text(stream, bold, 10, 445, 724, "COMPLETED", new Color(37, 99, 180));
                detail(stream, regular, bold, "Transaction reference", operation.getReference().toString(), 572);
                detail(stream, regular, bold, "Completed at", operation.getCompletedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), 512);
                String method = operation.getOperationType() == WalletOperationType.WITHDRAWAL
                        ? "Simulated withdrawal (no bank payout)"
                        : operation.getPaymentMethod() == null ? "Not recorded" : operation.getPaymentMethod().name().replace('_', ' ');
                detail(stream, regular, bold, "Payment method", method, 452);
                detail(stream, regular, bold, "Recorded balance after transaction", money(operation.getBalanceAfter()), 392);
                text(stream, regular, 9, 48, 342, "This recorded transaction balance is not your current cash balance.", Color.DARK_GRAY);
            }
            footer(document, regular);
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate wallet receipt", e);
        }
    }

    public byte[] getStatement(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1900 || to.getYear() > 9999
                || ChronoUnit.DAYS.between(from, to) > 365) {
            throw new BusinessRuleException("Choose a valid statement range of at most 366 days");
        }
        Long portfolioId = portfolioRepository.findByUserId(currentUser.getUserId())
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist")).getId();
        List<WalletOperation> operations = walletOperationRepository
                .findByPortfolio_IdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAscIdAsc(
                        portfolioId, WalletOperationStatus.COMPLETED, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), PageRequest.of(0, 1001));
        if (operations.size() > 1000) {
            throw new BusinessRuleException("The statement exceeds 1000 transactions. Choose a shorter date range");
        }
        BigDecimal deposits = BigDecimal.ZERO;
        BigDecimal withdrawals = BigDecimal.ZERO;
        for (WalletOperation operation : operations) {
            if (operation.getOperationType() == WalletOperationType.DEPOSIT) deposits = deposits.add(operation.getAmount());
            else if (operation.getOperationType() == WalletOperationType.WITHDRAWAL) withdrawals = withdrawals.add(operation.getAmount());
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            document.getDocumentInformation().setTitle("Cash Wallet Statement");
            String generated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            int index = 0;
            do {
                boolean first = document.getNumberOfPages() == 0;
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    heading(stream, regular, bold, "Wallet Statement");
                    text(stream, regular, 10, 48, 700, "Period: " + from + " to " + to + " (inclusive)", Color.DARK_GRAY);
                    text(stream, regular, 9, 48, 682, "Generated: " + generated, Color.DARK_GRAY);
                    if (first) {
                        box(stream, 48, 585, 499, 76, new Color(237, 244, 252));
                        text(stream, regular, 9, 60, 643, "Completed deposits", navy());
                        text(stream, bold, fit(bold, money(deposits), 145, 14), 60, 621, money(deposits), navy());
                        text(stream, regular, 9, 227, 643, "Completed withdrawals", navy());
                        text(stream, bold, fit(bold, money(withdrawals), 145, 14), 227, 621, money(withdrawals), navy());
                        text(stream, regular, 9, 394, 643, "Net funding", navy());
                        text(stream, bold, fit(bold, money(deposits.subtract(withdrawals)), 141, 14), 394, 621, money(deposits.subtract(withdrawals)), navy());
                        text(stream, regular, 9, 60, 599, "Completed transactions: " + operations.size(), navy());
                    }
                    float top = first ? 564 : 650;
                    tableHeader(stream, bold, top);
                    int rows = first ? 13 : 16;
                    for (int row = 0; row < rows && index < operations.size(); row++, index++) {
                        WalletOperation operation = operations.get(index);
                        float y = top - 24 - row * 32;
                        if (row % 2 == 0) box(stream, 48, y - 32, 499, 32, new Color(247, 249, 252));
                        text(stream, regular, 8, 56, y - 13, operation.getCompletedAt().toLocalDate().toString(), navy());
                        text(stream, regular, 8, 56, y - 25, operation.getCompletedAt().format(DateTimeFormatter.ofPattern("HH:mm:ss")), Color.DARK_GRAY);
                        text(stream, bold, 8, 148, y - 17, operation.getOperationType().name(), navy());
                        text(stream, regular, 8, 226, y - 17, operation.getReference().toString(), navy());
                        String amount = money(operation.getAmount());
                        float size = Math.min(9, 109 * 1000 / regular.getStringWidth(amount));
                        text(stream, regular, size, 539 - regular.getStringWidth(amount) / 1000 * size, y - 17, amount, navy());
                    }
                    if (operations.isEmpty()) text(stream, regular, 11, 56, top - 54, "No completed wallet transactions in this date range.", Color.DARK_GRAY);
                }
            } while (index < operations.size());
            footer(document, regular);
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate wallet statement", e);
        }
    }

    private Color navy() { return new Color(24, 45, 73); }

    private String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "USD " + format.format(amount);
    }

    private void heading(PDPageContentStream stream, PDFont regular, PDFont bold, String title) throws IOException {
        box(stream, 48, 804, 499, 4, new Color(37, 99, 180));
        text(stream, bold, 13, 48, 780, "INVESTMENT PORTFOLIO", navy());
        text(stream, regular, 9, 48, 761, "VIRTUAL CASH WALLET", new Color(37, 99, 180));
        text(stream, bold, 24, 48, 724, title, navy());
    }

    private void detail(PDPageContentStream stream, PDFont regular, PDFont bold, String label, String value, float y) throws IOException {
        text(stream, regular, 9, 48, y, label, Color.DARK_GRAY);
        text(stream, bold, 12, 48, y - 21, value, navy());
    }

    private void tableHeader(PDPageContentStream stream, PDFont bold, float top) throws IOException {
        box(stream, 48, top - 24, 499, 24, navy());
        text(stream, bold, 9, 56, top - 16, "Completed at", Color.WHITE);
        text(stream, bold, 9, 148, top - 16, "Type", Color.WHITE);
        text(stream, bold, 9, 226, top - 16, "Transaction reference", Color.WHITE);
        text(stream, bold, 9, 466, top - 16, "USD amount", Color.WHITE);
    }

    private void footer(PDDocument document, PDFont regular) throws IOException {
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(index), PDPageContentStream.AppendMode.APPEND, true, true)) {
                box(stream, 48, 85, 499, 1, new Color(210, 220, 233));
                text(stream, regular, 8, 48, 67, "Educational simulation only. No real money or bank payout is involved.", Color.DARK_GRAY);
                text(stream, regular, 7, 48, 51, "Dates and times are shown as recorded by the application.", Color.DARK_GRAY);
                text(stream, regular, 8, 485, 51, "Page " + (index + 1) + " of " + document.getNumberOfPages(), Color.DARK_GRAY);
            }
        }
    }

    private float fit(PDFont font, String value, float width, float size) throws IOException {
        // keep large totals inside their summary column
        return Math.min(size, width * 1000 / font.getStringWidth(value));
    }

    private void text(PDPageContentStream stream, PDFont font, float size, float x, float y, String value, Color color) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private void box(PDPageContentStream stream, float x, float y, float width, float height, Color color) throws IOException {
        stream.setNonStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.fill();
    }
}
