package com.example.drinkshop.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 備料（050、051）：誰做了什麼半成品、幾份，以及剩下的半成品倒掉幾份。
 * 只做紀錄：沒有半成品庫存，也不扣原料帳面。做半成品用掉的原料由物料消耗報表照材料用量計算。
 */
public final class PrepBatchDtos {
    private PrepBatchDtos() {}

    /**
     * 登記一筆備料。madeBy：做的人——POS 共用帳號登記時必填（在職員工）；個人帳號登記時一律記成自己，不用給。
     * qty：份數，最多兩位小數。note：備註，可省略。
     */
    public record Create(@NotNull @Positive Long prepId,
                         @NotNull @DecimalMin(value = "0.01", message = "份數要大於 0")
                         @Digits(integer = 8, fraction = 2, message = "份數最多兩位小數") BigDecimal qty,
                         @Positive Long madeBy,
                         @Size(max = 200, message = "備註最多 200 個字") String note) {}

    /** 登記一筆半成品報廢（051）。wastedBy 規則和 madeBy 一樣；note 是原因，必填。 */
    public record WasteCreate(@NotNull @Positive Long prepId,
                              @NotNull @DecimalMin(value = "0.01", message = "份數要大於 0")
                              @Digits(integer = 8, fraction = 2, message = "份數最多兩位小數") BigDecimal qty,
                              @Positive Long wastedBy,
                              @NotBlank(message = "請填寫報廢原因") @Size(max = 200, message = "原因最多 200 個字") String note) {}

    /** 一筆備料紀錄。madeAt 是登記時間（台北時間，yyyy-MM-ddTHH:mm:ss）。 */
    public record Row(long batchId, String businessDate, long prepId, String prepName, BigDecimal qty,
                      long madeBy, String madeByName, long recordedBy, String recordedByName, String madeAt, String note) {}

    /** 一筆半成品報廢紀錄。 */
    public record WasteRow(long wasteId, String businessDate, long prepId, String prepName, BigDecimal qty,
                           long wastedBy, String wastedByName, long recordedBy, String recordedByName, String wastedAt, String note) {}

    /**
     * 一種半成品這一天的總覽：made 做了幾份（備料）、used 訂單用了幾份（飲品配方＋加料，取消的不算）、
     * wasted 倒掉幾份、remaining＝made − used − wasted（只是參考，不是庫存；負數代表有做了沒登記）。
     */
    public record Summary(long prepId, String name, String category, BigDecimal made, BigDecimal used,
                          BigDecimal wasted, BigDecimal remaining) {}

    /** 可以登記的半成品。 */
    public record PrepOption(long prepId, String name, String category) {}

    /** 某一天的備料：備料紀錄、報廢紀錄（新的在前）與各半成品總覽（有做、有用或有報廢的才列）。 */
    public record Day(String businessDate, List<Row> rows, List<WasteRow> wastes, List<Summary> summary) {}
}
