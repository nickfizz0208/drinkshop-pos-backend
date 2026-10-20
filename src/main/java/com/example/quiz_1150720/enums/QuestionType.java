package com.example.quiz_1150720.enums;

import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES)
public enum QuestionType {
	SINGLE,   // 單選
	MULTIPLE, // 多選
	TEXT      // 簡答
}