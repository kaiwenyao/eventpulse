package dev.kaiwen.eventpulse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    /** USER 普通用户；ORGANISER 主办方，可以发布活动。 */
    @Column(nullable = false)
    private String role;

    /** 钱包余额（分）。注册时为零，个人中心可充值，取消预订时按实付金额退回。 */
    @Column(name = "wallet_cents", nullable = false)
    private long walletCents;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public long getWalletCents() {
        return walletCents;
    }

    public void setWalletCents(long walletCents) {
        this.walletCents = walletCents;
    }
}
