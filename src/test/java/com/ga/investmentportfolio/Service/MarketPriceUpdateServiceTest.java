package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Repository.AssetRepository;
import com.ga.investmentportfolio.Repository.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MarketPriceUpdateServiceTest {
    final MarketPriceUpdateService service = new MarketPriceUpdateService(mock(AssetRepository.class), mock(PriceHistoryRepository.class));

    @Test void generatedPricesRespectChangeRangeAndMoneyScale() {
        for (int i = 0; i < 100; i++) {
            BigDecimal price = service.calculateNewPrice(new BigDecimal("100.00"));
            assertEquals(2, price.scale());
            assertTrue(price.compareTo(new BigDecimal("99.50")) >= 0);
            assertTrue(price.compareTo(new BigDecimal("100.50")) <= 0);
        }
    }

    @Test void minimumPriceRemainsOneCent() {
        for (int i = 0; i < 100; i++) assertEquals(new BigDecimal("0.01"), service.calculateNewPrice(new BigDecimal("0.01")));
        assertEquals(new BigDecimal("0.01"), service.calculateNewPrice(BigDecimal.ZERO));
    }
}
