package com.ga.investmentportfolio.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ThreadPoolConfig {
    //class that defines how the thread pool should operate

    //method that creates and configures our thread pool for orders
    @Bean
    public ThreadPoolTaskExecutor orderExecutor() {
        //control how many investment orders can execute simultaneously and how many can wait
        ThreadPoolTaskExecutor executor= new ThreadPoolTaskExecutor();

        //set num of threads normally available
        executor.setCorePoolSize(3);

        //set max number of threads allowed
        executor.setMaxPoolSize(5);

        //set num of tasks that can wait
        executor.setQueueCapacity(10);

        //initialize executor
        executor.initialize();

        //return executor
        return executor;
    }

    //method that creates and configures our thread pool for market price
    @Bean
    public ThreadPoolTaskExecutor marketExecutor() {
        //control how many market price updates can execute simultaneously and how many can wait
        ThreadPoolTaskExecutor executor= new ThreadPoolTaskExecutor();

        //set num of threads normally available
        executor.setCorePoolSize(3);

        //set max number of threads allowed
        executor.setMaxPoolSize(5);

        //set num of tasks that can wait
        executor.setQueueCapacity(20);

        executor.setThreadNamePrefix("market-worker-");

        //initialize executor
        executor.initialize();

        //return executor
        return executor;
    }

}
