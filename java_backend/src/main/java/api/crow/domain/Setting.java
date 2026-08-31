package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "settings")
@Getter
@Setter
public class Setting {

    @Id
    @Column(name = "clave", length = 64)
    private String clave;

    @Column(name = "valor")
    private String valor;

    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;
}