package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "bots")
@Getter
@Setter
public class Bot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", length = 100, nullable = false)
    private String nombre;

    @Column(name = "token_cifrado", columnDefinition = "VARBINARY(1024)", nullable = false)
    private byte[] tokenCifrado;

    /** ID numérico del bot en Telegram (viene de getMe al registrarlo). */
    @Column(name = "telegram_id")
    private Long telegramId;

    /** @username del bot en Telegram (viene de getMe al registrarlo). */
    @Column(name = "username", length = 64)
    private String username;

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
