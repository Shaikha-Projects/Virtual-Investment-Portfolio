package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    //to find email verification token by its unique token value
    Optional<EmailVerificationToken> findByToken(String token);
}
