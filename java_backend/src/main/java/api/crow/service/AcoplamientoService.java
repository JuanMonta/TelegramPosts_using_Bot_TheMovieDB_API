package api.crow.service;

import api.crow.domain.Acoplamiento;
import api.crow.domain.Canal;
import api.crow.repository.AcoplamientoRepository;
import api.crow.repository.CanalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AcoplamientoService {

    private final AcoplamientoRepository repo;
    private final CanalRepository canalRepo;

    public List<Map<String, Object>> listar() {
        return repo.findAll().stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> crear(String nombre) {
        validarNombre(nombre);
        Acoplamiento a = new Acoplamiento();
        a.setNombre(nombre.trim());
        a.setActivo(true);
        return toMap(repo.save(a));
    }

    @Transactional
    public Map<String, Object> actualizar(Long id, String nombre) {
        Acoplamiento a = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El acoplamiento no existe"));
        if (nombre != null && !nombre.isBlank()) {
            validarNombre(nombre);
            a.setNombre(nombre.trim());
        }
        return toMap(repo.save(a));
    }

    @Transactional
    public Map<String, Object> agregarCanal(Long id, Long canalId) {
        Acoplamiento a = repo.findConCanalesById(id)
                .orElseThrow(() -> new IllegalArgumentException("El acoplamiento no existe"));
        Canal c = canalRepo.findById(canalId)
                .orElseThrow(() -> new IllegalArgumentException("El canal no existe"));
        if (a.getCanales().stream().noneMatch(x -> x.getId().equals(canalId))) {
            a.getCanales().add(c);
        }
        return toMap(repo.save(a));
    }

    @Transactional
    public Map<String, Object> quitarCanal(Long id, Long canalId) {
        Acoplamiento a = repo.findConCanalesById(id)
                .orElseThrow(() -> new IllegalArgumentException("El acoplamiento no existe"));
        a.getCanales().removeIf(x -> x.getId().equals(canalId));
        return toMap(repo.save(a));
    }

    @Transactional
    public void eliminar(Long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("El acoplamiento no existe");
        }
        repo.deleteById(id);
    }

    private void validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank() || nombre.trim().length() > 100) {
            throw new IllegalArgumentException("El nombre es obligatorio (máx. 100 caracteres)");
        }
    }

    private Map<String, Object> toMap(Acoplamiento a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("nombre", a.getNombre());
        m.put("activo", a.getActivo());
        m.put("canales", a.getCanales().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.getId());
            cm.put("nombre", c.getNombre());
            cm.put("chat_id", c.getChatId());
            cm.put("tipo", c.getTipo());
            return cm;
        }).toList());
        return m;
    }
}
