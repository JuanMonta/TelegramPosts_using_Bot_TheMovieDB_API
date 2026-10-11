package api.crow.repository;

import api.crow.domain.ConfigApp;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigAppRepository extends JpaRepository<ConfigApp, Long> {
}
