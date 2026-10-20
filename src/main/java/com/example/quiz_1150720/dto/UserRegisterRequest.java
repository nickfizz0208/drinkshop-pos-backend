package com.example.quiz_1150720.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class UserRegisterRequest {

    @NotBlank(message = "帳號不得為空")
    private String account;

    @NotBlank(message = "密碼不得為空")
    private String password;

    @NotBlank(message = "姓名不得為空")
    private String name;

    @NotBlank(message = "電話不得為空")
    private String phone;

    @NotBlank(message = "Email 不得為空")
    @Email(message = "Email 格式不正確")
    private String email;

    public UserRegisterRequest() {
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}