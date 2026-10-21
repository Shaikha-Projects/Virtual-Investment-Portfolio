package com.ga.investmentportfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InvestmentPortfolioApplication {

	public static void main(String[] args) {
		SpringApplication.run(InvestmentPortfolioApplication.class, args);
	}

}
