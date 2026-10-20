package com.example.quiz_1150720.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginResponse {

    private String token;
    private String account;
    private String name;

    // 手寫無參建構子
    public UserLoginResponse() {
    }

    // 關鍵！手寫全參建構子（讓你的 Eclipse 100% 抓得到，解救第 66 行）
    public UserLoginResponse(String token, String account, String name) {
        this.token = token;
        this.account = account;
        this.name = name;
    }

    // 手寫 Getter / Setter（防止轉 JSON 時欄位遺失）
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}