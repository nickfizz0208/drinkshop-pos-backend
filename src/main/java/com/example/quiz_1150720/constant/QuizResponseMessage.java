package com.example.quiz_1150720.constant;

public class QuizResponseMessage {
    // 驗證訊息
    public static final String TITLE_NOT_BLANK = "問卷標題不得為空";
    public static final String DESCRIPTION_NOT_BLANK = "問卷說明不得為空";
    public static final String START_DATE_NOT_NULL = "開始日期不得為空";
    public static final String END_DATE_NOT_NULL = "結束日期不得為空";
    public static final String QUESTIONS_NOT_EMPTY = "問卷至少須包含一個問題";

    public static final String Q_TITLE_NOT_BLANK = "問題題目不得為空";
    public static final String Q_TYPE_NOT_NULL = "問題類型不得為空";
    public static final String Q_NO_NOT_NULL = "問題編號不得為空";

    // 問卷業務訊息
    public static final String QUIZ_NOT_FOUND_FOR_UPDATE = "問卷 ID 不存在，無法更新";
    public static final String QUIZ_NOT_FOUND = "找不到該問卷";
    public static final String END_DATE_BEFORE_START_DATE = "結束日期不得早於開始日期";
    public static final String OPTION_REQUIRED_FOR_CHOICE = "問題 [%s] 為單選或多選題，必須包含選項";
    public static final String OPTION_NO_NOT_NULL_MSG = "問題 [%s] 的選項編號不得為空";
    public static final String OPTION_TEXT_NOT_BLANK_MSG = "問題 [%s] 的選項內容不得為空";
    public static final String OPTION_NOT_ALLOWED_FOR_TEXT = "問題 [%s] 為簡答題，不得包含選項";

    // 成功回傳提示
    public static final String CREATE_SUCCESS = "問卷建立成功";
    public static final String UPDATE_SUCCESS = "問卷更新成功";
    public static final String GET_LIST_SUCCESS = "取得問卷列表成功";
    public static final String GET_DETAIL_SUCCESS = "取得問卷詳情成功";
    public static final String DELETE_SUCCESS = "問卷刪除成功";
    public static final String SUBMIT_QUIZ_SUCCESS = "問卷填寫送出成功"; // 10/16 新增

    // 會員相關訊息
    public static final String USER_ACCOUNT_EXISTS = "此帳號已被註冊";
    public static final String USER_EMAIL_EXISTS = "此 Email 已被使用";
    public static final String USER_NOT_FOUND = "帳號不存在";
    public static final String PASSWORD_MISMATCH = "密碼錯誤";
    public static final String REGISTER_SUCCESS = "註冊成功";
    public static final String LOGIN_SUCCESS = "登入成功";
    public static final String LOGOUT_SUCCESS = "登出成功";
}