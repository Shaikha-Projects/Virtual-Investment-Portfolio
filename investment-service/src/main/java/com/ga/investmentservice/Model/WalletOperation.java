package com.ga.investmentservice.Model;

import com.ga.investmentservice.Enums.SimulatedPaymentMethod;
import com.ga.investmentservice.Enums.WalletOperationStatus;
import com.ga.investmentservice.Enums.WalletOperationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString(exclude = {"portfolio"})
@Entity
@Table(name="wallet_operations", schema = "public",
        uniqueConstraints = {
                @UniqueConstraint(name = "wallet_operations_reference_unique", columnNames = "reference"),
                @UniqueConstraint(name = "wallet_operations_idempotency_unique", columnNames = {"portfolio_id", "idempotency_key"}),
                @UniqueConstraint(name = "wallet_operations_provider_invoice_unique", columnNames = "provider_invoice_id"),
                @UniqueConstraint(name = "wallet_operations_provider_payment_unique", columnNames = "provider_payment_id"),
                @UniqueConstraint(name = "wallet_operations_stripe_session_unique", columnNames = "stripe_session_id")
        },
        indexes = @Index(name = "wallet_operations_history_idx", columnList = "portfolio_id, created_at DESC, id DESC"))
public class WalletOperation {

    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false)
    private UUID reference;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "operation_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private WalletOperationType operationType;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column (nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "payment_method", length = 32)
    @Enumerated(EnumType.STRING)
    private SimulatedPaymentMethod paymentMethod;

    @Column (nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private WalletOperationStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "balance_after", precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6) without time zone")
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "completed_at", columnDefinition = "timestamp(6) without time zone")
    private LocalDateTime completedAt;

    @Column(name = "provider_invoice_id")
    private Long providerInvoiceId;

    @Column(name = "provider_payment_id", length = 128)
    private String providerPaymentId;

    @Column(name = "provider_invoice_status", length = 32)
    private String providerInvoiceStatus;

    @Column(name = "provider_transaction_status", length = 32)
    private String providerTransactionStatus;

    @Column(name = "payment_url", columnDefinition = "TEXT")
    private String paymentUrl;

    @Column(name = "stripe_session_id", length = 255)
    private String stripeSessionId;

    @Column(name = "stripe_creation_request", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> stripeCreationRequest;

    //single portfolio can contain many wallet operations
    //many wallet operations point to one portfolio
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", referencedColumnName = "id", nullable = false,
            foreignKey = @ForeignKey(name = "wallet_operations_portfolio_fk"))
    private Portfolio portfolio;

}
