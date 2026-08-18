package com.distributedjudge.repository;

import com.distributedjudge.model.Problem;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemRepository extends JpaRepository<Problem, Long> {
    @EntityGraph(attributePaths = "testCases")
    Optional<Problem> findWithTestCasesById(Long id);
}
