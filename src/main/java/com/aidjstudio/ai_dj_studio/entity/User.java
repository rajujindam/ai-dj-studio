package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
@AllArgsConstructor
@Data
@NoArgsConstructor
public class User {

    @Id
    private Long id;

    private String email;

    @Column("password_hash")
    @ToString.Exclude
    private String passwordHash;

    @Column("display_name")
    private String displayName;

    private String role;
}