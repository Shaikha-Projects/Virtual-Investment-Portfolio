package com.ga.investmentportfolio.DTO.Response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProfileResponse {

    private String firstName;
    private String lastName;
    private String phoneNumber;
    private String emailAddress;
    private String profilePicture;
}
