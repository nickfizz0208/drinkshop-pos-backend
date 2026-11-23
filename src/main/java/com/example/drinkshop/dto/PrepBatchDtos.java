package com.example.drinkshop.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 備料（050）：誰煮了什麼半成品、幾份。只做紀錄，不扣原料、不改半成品數量。 */
public final class PrepBatchDtos {
    private PrepBatchDtos() {}

    /**
     * 登記一筆備料。madeBy：煮的人——POS 共用帳號登記時必填（在職員工）；個人帳號登記時一律記成自己，不用給。
     * qty：份數，最多兩位小數。note：備註，可省略。
     */
    public record Create(@NotNull @Positive Long prepId,
                         @NotNull @DecimalMin(value = "0.01", message = "份數要大於 0")
                         @Digits(integer = 8, fraction = 2, message = "份數最多兩位小數") BigDecimal qty,
                         @Positive Long madeBy,
                         @Size(max = 200, message = "備註最多 200 個字") String note) {}

    /** 一筆備料紀錄。madeAt 是登記時間（台北時間，yyyy-MM-ddTHH:mm:ss）。 */
    public record Row(long batchId, String businessDate, long prepId, String prepName, BigDecimal qty,
                      long madeBy, String madeByName, long recordedBy, String recordedByName, String madeAt, String note) {}

    /** 可以登記的半成品。 */
    public record PrepOption(long prepId, String name, String category) {}

    /** 某一天的備料紀錄（新的在前）。 */
    public record Day(String businessDate, List<Row> rows) {}
}
