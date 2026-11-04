package com.agentoffice.entity.usage;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Chỉ-thêm (trigger DB canh): đổi giá = thêm bản ghi mới; bản cũ chỉ được đóng bằng effectiveTo. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "model_price")
public class ModelPrice {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String provider;
    private String model;
    private long inputMicroPerMtok;
    private long outputMicroPerMtok;
    private Long cachedInputMicroPerMtok;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    @Column(name = "is_local")
    private boolean local;
    private String note;

    public ModelPrice(String provider, String model, long inputMicroPerMtok, long outputMicroPerMtok,
                      Long cachedInputMicroPerMtok, Instant effectiveFrom, boolean local, String note) {
        this.provider = provider;
        this.model = model;
        this.inputMicroPerMtok = inputMicroPerMtok;
        this.outputMicroPerMtok = outputMicroPerMtok;
        this.cachedInputMicroPerMtok = cachedInputMicroPerMtok;
        this.effectiveFrom = effectiveFrom;
        this.local = local;
        this.note = note;
    }

    /** Duy nhất thay đổi được sau khi tạo: đóng khoảng hiệu lực. */
    public void closeAt(Instant t) { this.effectiveTo = t; }
}
