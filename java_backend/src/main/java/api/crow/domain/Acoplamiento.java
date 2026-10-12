package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "acoplamientos")
@Getter
@Setter
public class Acoplamiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", length = 100, nullable = false)
    private String nombre;

    @Column(name = "activo")
    private Boolean activo = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "acoplamiento_canales",
            joinColumns = @JoinColumn(name = "acoplamiento_id"),
            inverseJoinColumns = @JoinColumn(name = "canal_id")
    )
    private List<Canal> canales = new ArrayList<>();

    @Column(name = "creado_en", updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    void prePersist() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
        if (activo == null) activo = true;
    }
}
