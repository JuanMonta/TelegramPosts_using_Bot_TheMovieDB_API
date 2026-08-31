package api.crow.repository;

import api.crow.domain.CaptionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaptionTemplateRepository extends JpaRepository<CaptionTemplate, Long> {

    Optional<CaptionTemplate> findByIsDefaultTrue();

    Optional<CaptionTemplate> findByName(String name);

    boolean existsByName(String name);

    List<CaptionTemplate> findAllByOrderByIsDefaultDescNameAsc();
}