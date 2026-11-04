package com.agentoffice.entity.system;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "system_state")
public class SystemState {
    @Id
    private String key;
    @Setter
    private String value;

    public SystemState(String key, String value) { this.key = key; this.value = value; }

}
