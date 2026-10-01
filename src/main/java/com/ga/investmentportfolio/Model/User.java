package com.ga.investmentportfolio.Model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString(exclude = {"password","portfolio", "watchlists"})
@Entity
@Table(name="users")
public class User {


    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column (nullable = false)
    private String phoneNumber;

    @Column (nullable = false, unique = true)
    private String emailAddress;

    @Column (nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column (nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column (nullable = false)
    private UserStatus status;

    @Column
    private String profilePicture;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    //user can have one profile only
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Portfolio portfolio;

    //user can have many watchlist
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Watchlist> watchlists;

    //one user may have more than one verification token
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<EmailVerificationToken> emailVerificationTokens;

    //one user may have more than one password rest token
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PasswordResetToken> passwordResetTokens;

}
