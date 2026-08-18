package com.distributedjudge.service;

import com.distributedjudge.model.Problem;
import com.distributedjudge.model.TestCase;
import com.distributedjudge.repository.ProblemRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {
    private final ProblemRepository problemRepository;

    public DataInitializer(ProblemRepository problemRepository) {
        this.problemRepository = problemRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (problemRepository.count() > 0) {
            return;
        }

        Problem sum = new Problem();
        sum.setTitle("Pair Sum");
        sum.setDifficulty("Easy");
        sum.setStatement("Read two integers and print their sum.");
        sum.setSampleInput("2 3");
        sum.setSampleOutput("5");
        sum.addTestCase(new TestCase("2 3", "5", false));
        sum.addTestCase(new TestCase("10 -4", "6", true));
        sum.addTestCase(new TestCase("999 1", "1000", true));
        problemRepository.save(sum);

        Problem max = new Problem();
        max.setTitle("Maximum Number");
        max.setDifficulty("Easy");
        max.setStatement("Read N followed by N integers and print the maximum value.");
        max.setSampleInput("5\n8 1 3 9 2");
        max.setSampleOutput("9");
        max.addTestCase(new TestCase("5\n8 1 3 9 2", "9", false));
        max.addTestCase(new TestCase("4\n-7 -3 -9 -4", "-3", true));
        max.addTestCase(new TestCase("3\n42 42 1", "42", true));
        problemRepository.save(max);

        Problem reverse = new Problem();
        reverse.setTitle("Reverse Words");
        reverse.setDifficulty("Medium");
        reverse.setStatement("Read a line and print the words in reverse order.");
        reverse.setSampleInput("distributed code judge");
        reverse.setSampleOutput("judge code distributed");
        reverse.addTestCase(new TestCase("distributed code judge", "judge code distributed", false));
        reverse.addTestCase(new TestCase("spring boot api", "api boot spring", true));
        reverse.addTestCase(new TestCase("safe async workers", "workers async safe", true));
        problemRepository.save(reverse);
    }
}
