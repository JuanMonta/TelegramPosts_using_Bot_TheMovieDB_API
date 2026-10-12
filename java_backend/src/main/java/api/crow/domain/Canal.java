package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "canales")
@Getter
@Setter
public class Canal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", length = 100, nullable = false)
    private String nombre;

    @Column(name = "chat_id", length = 64, nullable = false)
    private String chatId;

    @Column(name = "tipo", length = 8, nullable = false)
    private String tipo;

    @Column(name = "bot_id")
    private Long botId;

    @Column(name = "chat_tipo", length = 16)
    private String chatTipo;

    @Column(name = "activo")
    private Boolean activo = true;

    @Column(name = "creado_en", updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    void prePersist() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
        if (activo == null) activo = true;
    }
}
