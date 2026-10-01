package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    //to register
    boolean existsByEmailAddress(String emailAddress);

    //to find use by email address during login
    Optional<User> findByEmailAddress(String emailAddress);

}
