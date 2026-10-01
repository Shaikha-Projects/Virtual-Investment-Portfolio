package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.EmailVerificationToken;
import com.ga.investmentportfolio.Model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    //to find password reset token by its unique token value
    Optional<PasswordResetToken> findByToken(String token);
}
