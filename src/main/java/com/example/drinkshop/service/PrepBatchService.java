package com.example.drinkshop.service;

import com.example.drinkshop.dto.PrepBatchDtos.*;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 備料（050）：登記誰煮了什麼半成品、幾份。
 * POS 共用帳號登記時要選煮的人（在職員工）；個人帳號登記時記成自己。只做紀錄，不扣原料帳面、不改半成品數量。
 */
@Service
public class PrepBatchService {
    private final PosRepository repo;
    private final Clock clock;
    public PrepBatchService(PosRepository repo, Clock clock) { this.repo = repo; this.clock = clock; }

    @Transactional(readOnly = true)
    public List<PrepOption> preps() {
        return repo.rows("SELECT prep_id, name, category FROM prep_item ORDER BY category, prep_id").stream()
            .map(r -> new PrepOption(PosRepository.number(r, "prep_id"), (String) r.get("name"), (String) r.get("category")))
            .toList();
    }

    /** 某一天的備料紀錄；date 是 null＝今天。 */
    @Transactional(readOnly = true)
    public Day day(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(clock) : date;
        var rows = repo.rows(SELECT + " WHERE b.business_date = ? ORDER BY b.made_at DESC, b.batch_id DESC", target)
            .stream().map(PrepBatchService::row).toList();
        return new Day(target.toString(), rows);
    }

    @Transactional
    public Row create(Create request, Staff current) {
        repo.rows("SELECT prep_id FROM prep_item WHERE prep_id = ?", request.prepId()).stream().findFirst()
            .orElseThrow(() -> ApiException.missing("找不到這個半成品"));
        long madeBy;
        if ("POS".equals(current.level())) {
            // POS 機登記：要選是誰煮的，只能選在職員工（POS 共用帳號不是真人）
            if (request.madeBy() == null) throw ApiException.bad("請選擇是誰煮的");
            var staff = repo.rows("SELECT staff_id FROM staff_account WHERE staff_id = ? AND active = TRUE AND level <> 'POS'",
                request.madeBy());
            if (staff.isEmpty()) throw ApiException.bad("煮的人要是在職員工");
            madeBy = request.madeBy();
        } else {
            // 個人帳號登記：記成自己
            madeBy = current.id();
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        long id = repo.insert("batch_id", """
            INSERT INTO prep_batch(business_date, prep_id, qty, made_by, recorded_by, made_at, note)
            VALUES (?,?,?,?,?,?,?)
            """, now.toLocalDate(), request.prepId(), request.qty(), madeBy, current.id(), now, note);
        return row(repo.one(SELECT + " WHERE b.batch_id = ?", id));
    }

    private static final String SELECT = """
        SELECT b.batch_id, b.business_date, b.prep_id, x.name AS prep_name, b.qty, b.made_by, m.name AS made_by_name,
               b.recorded_by, r.name AS recorded_by_name, b.made_at, b.note
          FROM prep_batch b
          JOIN prep_item x ON x.prep_id = b.prep_id
          JOIN staff_account m ON m.staff_id = b.made_by
          JOIN staff_account r ON r.staff_id = b.recorded_by
        """;

    private static Row row(Map<String, Object> r) {
        Object madeAt = r.get("made_at");
        String madeAtText = madeAt instanceof java.sql.Timestamp t ? t.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).toString()
            : madeAt instanceof LocalDateTime l ? l.truncatedTo(ChronoUnit.SECONDS).toString() : String.valueOf(madeAt);
        Object date = r.get("business_date");
        String dateText = date instanceof java.sql.Date d ? d.toLocalDate().toString() : String.valueOf(date);
        return new Row(PosRepository.number(r, "batch_id"), dateText, PosRepository.number(r, "prep_id"), (String) r.get("prep_name"),
            new BigDecimal(r.get("qty").toString()), PosRepository.number(r, "made_by"), (String) r.get("made_by_name"),
            PosRepository.number(r, "recorded_by"), (String) r.get("recorded_by_name"), madeAtText, (String) r.get("note"));
    }
}
