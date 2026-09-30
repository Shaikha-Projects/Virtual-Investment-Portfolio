package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    //to register
    boolean existsByEmailAddress(String emailAddress);

    //to login
    User findUserByEmailAddress(String emailAddress);

}
