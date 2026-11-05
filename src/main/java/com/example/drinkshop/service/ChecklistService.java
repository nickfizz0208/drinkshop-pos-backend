package com.example.drinkshop.service;

import com.example.drinkshop.dto.ChecklistDtos.*;
import com.example.drinkshop.entity.ChecklistItem;
import com.example.drinkshop.entity.ChecklistItem.Frequency;
import com.example.drinkshop.entity.ChecklistRecord;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.ChecklistItemRepository;
import com.example.drinkshop.repository.ChecklistRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.List;

/**
 * 檢查表：工作項目由店長以上管理；打勾時記錄「做事的人」（前端選擇的 staffId），不是登入帳號。
 * 打勾的人必須是目前在班的人（shift_staff 今天還沒打卡下班）。
 * 個人帳號只能記自己、只能取消自己的紀錄；POS 共用帳號可替在班的人打勾；店長、總部可取消任何紀錄。
 */
@Service
@Transactional
public class ChecklistService {
    private final ChecklistItemRepository items;
    private final ChecklistRecordRepository records;
    private final ShiftStaffService shiftStaff;
    private final Clock clock;

    public ChecklistService(ChecklistItemRepository items, ChecklistRecordRepository records,
                            ShiftStaffService shiftStaff, Clock clock) {
        this.items = items; this.records = records; this.shiftStaff = shiftStaff; this.clock = clock;
    }

    /** DAILY → 2026-11-05；WEEKLY → 2026-W45（ISO 週，週一開始）。 */
    public static String period(Frequency frequency, LocalDate date) {
        return switch (frequency) {
            case DAILY -> date.toString();
            case WEEKLY -> String.format("%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        };
    }

    // ---------- 工作項目 ----------

    @Transactional(readOnly = true)
    public List<ItemResponse> listItems(boolean includeInactive) {
        var list = includeInactive ? items.findAllByOrderByFrequencyAscIdAsc()
                                   : items.findByActiveTrueOrderByFrequencyAscIdAsc();
        return list.stream().map(ItemResponse::from).toList();
    }

    public ItemResponse createItem(CreateItem request, String username) {
        var manager = shiftStaff.requireManager(username);
        int target = request.targetCount() == null ? 1 : request.targetCount();
        var item = new ChecklistItem(request.name().trim(), request.frequency(), target, manager.id());
        return ItemResponse.from(items.saveAndFlush(item));
    }

    public ItemResponse updateItem(long itemId, UpdateItem request, String username) {
        shiftStaff.requireManager(username);
        var item = findItem(itemId);
        item.setName(request.name().trim());
        item.setFrequency(request.frequency());
        item.setTargetCount(request.targetCount());
        item.setActive(request.active());
        item.setUpdatedAt(LocalDateTime.now(clock));
        return ItemResponse.from(items.saveAndFlush(item));
    }

    // ---------- 打勾紀錄 ----------

    /** 檢查表畫面：所有啟用中的項目，以及指定日期所屬那一期的完成狀況。 */
    @Transactional(readOnly = true)
    public List<BoardRow> board(LocalDate date) {
        var day = date == null ? LocalDate.now(clock) : date;
        return items.findByActiveTrueOrderByFrequencyAscIdAsc().stream().map(item -> {
            String period = period(item.getFrequency(), day);
            var done = records.findByItemIdAndPeriodOrderByDoneAtAsc(item.getId(), period)
                .stream().map(this::toResponse).toList();
            return new BoardRow(item.getId(), item.getName(), item.getFrequency(), period,
                item.getTargetCount(), done.size(), done.size() >= item.getTargetCount(), done);
        }).toList();
    }

    public RecordResponse check(CreateRecord request, String username) {
        var actor = shiftStaff.actor(username);
        long staffId;
        if (ShiftStaffService.isPos(actor)) {
            if (request.staffId() == null) throw ApiException.bad("請選擇做這件事的人");
            staffId = request.staffId();
        } else {
            if (request.staffId() != null && request.staffId() != actor.id())
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "只能記錄自己做的事");
            staffId = actor.id();
        }
        var item = findItem(request.itemId());
        if (!item.isActive()) throw ApiException.conflict("此工作項目已停用");
        shiftStaff.requireActiveStaff(staffId);
        if (!shiftStaff.isOnDuty(staffId)) throw ApiException.conflict("目前不在班，請先在 POS 機打卡上班");
        var now = LocalDateTime.now(clock);
        var record = new ChecklistRecord(item.getId(), staffId,
            period(item.getFrequency(), now.toLocalDate()), now);
        return toResponse(records.saveAndFlush(record));
    }

    /** 取消打勾：直接刪除該筆紀錄。個人帳號只能取消自己的。 */
    public void uncheck(long recordId, String username) {
        var actor = shiftStaff.actor(username);
        var record = records.findById(recordId)
            .orElseThrow(() -> ApiException.missing("找不到這筆打勾紀錄"));
        if (!ShiftStaffService.isPos(actor) && !shiftStaff.isManager(actor) && !record.getStaffId().equals(actor.id()))
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "只能取消自己的紀錄");
        records.delete(record);
    }

    // ---------- 共用 ----------

    private ChecklistItem findItem(long itemId) {
        return items.findById(itemId).orElseThrow(() -> ApiException.missing("找不到這個工作項目"));
    }

    private RecordResponse toResponse(ChecklistRecord r) {
        return new RecordResponse(r.getId(), r.getItemId(), r.getStaffId(), shiftStaff.staffName(r.getStaffId()),
            r.getPeriod(), r.getDoneAt());
    }
}
