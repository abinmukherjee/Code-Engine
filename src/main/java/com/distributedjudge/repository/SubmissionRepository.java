package com.distributedjudge.repository;

import com.distributedjudge.model.Submission;
import com.distributedjudge.model.SubmissionState;
import com.distributedjudge.model.Verdict;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    @Override
    @EntityGraph(attributePaths = "problem")
    Optional<Submission> findById(Long id);

    @EntityGraph(attributePaths = "problem")
    List<Submission> findTop10ByOrderByCreatedAtDesc();

    long countByState(SubmissionState state);

    long countByVerdict(Verdict verdict);
}
