package com.example.drinkshop.service;

import com.example.drinkshop.dto.ShiftStaffDtos.Response;
import com.example.drinkshop.dto.ShiftStaffDtos.Update;
import com.example.drinkshop.entity.ShiftStaff;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.DailyShiftRepository;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.repository.ShiftStaffRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下班打卡。打卡選名字即可；修改、刪除紀錄限店長以上（店長需用自己的帳號登入）。
 * 必須當天已開班（daily_shift 為 OPEN）才能上班打卡；同一人還在班時不能重複上班打卡。
 */
@Service
@Transactional
public class ShiftStaffService {
    private static final Set<String> MANAGER_LEVELS = Set.of("MANAGER", "HQ");

    private final ShiftStaffRepository shiftStaff;
    private final DailyShiftRepository shifts;
    private final OrderService orders;
    private final PosRepository pos;
    private final Clock clock;

    public ShiftStaffService(ShiftStaffRepository shiftStaff, DailyShiftRepository shifts,
                             OrderService orders, PosRepository pos, Clock clock) {
        this.shiftStaff = shiftStaff; this.shifts = shifts; this.orders = orders; this.pos = pos; this.clock = clock;
    }

    public LocalDate today() { return LocalDate.now(clock); }

    // ---------- 打卡 ----------

    public Response clockIn(long staffId) {
        requireActiveStaff(staffId);
        var date = today();
        var shift = shifts.findById(date).orElseThrow(() -> ApiException.conflict("今天還沒開班，不能打卡上班"));
        if (!"OPEN".equalsIgnoreCase(shift.getStatus())) throw ApiException.conflict("今天已經關班，不能打卡上班");
        if (shiftStaff.existsByBusinessDateAndStaffIdAndClockOutAtIsNull(date, staffId))
            throw ApiException.conflict("這位員工已經在班，請先打卡下班");
        return toResponse(shiftStaff.saveAndFlush(new ShiftStaff(date, staffId, LocalDateTime.now(clock))));
    }

    public Response clockOut(long staffId) {
        var record = shiftStaff.findFirstByBusinessDateAndStaffIdAndClockOutAtIsNull(today(), staffId)
            .orElseThrow(() -> ApiException.conflict("這位員工目前不在班"));
        record.setClockOutAt(LocalDateTime.now(clock));
        return toResponse(shiftStaff.saveAndFlush(record));
    }

    // ---------- 查詢 ----------

    /** 目前在班的人（今天、還沒打卡下班）。 */
    @Transactional(readOnly = true)
    public List<Response> onDuty() {
        return shiftStaff.findByBusinessDateAndClockOutAtIsNullOrderByClockInAtAsc(today())
            .stream().map(this::toResponse).toList();
    }

    /** 某營業日的全部打卡紀錄（不填為今天）。 */
    @Transactional(readOnly = true)
    public List<Response> list(LocalDate date) {
        return shiftStaff.findByBusinessDateOrderByClockInAtAsc(date == null ? today() : date)
            .stream().map(this::toResponse).toList();
    }

    /** 給其他功能使用（例如 checklist 打勾）：這位員工今天是否在班。 */
    @Transactional(readOnly = true)
    public boolean isOnDuty(long staffId) {
        return shiftStaff.existsByBusinessDateAndStaffIdAndClockOutAtIsNull(today(), staffId);
    }

    // ---------- 店長修改 ----------

    public Response update(long id, Update request, String username) {
        var manager = requireManager(username);
        var record = find(id);
        if (request.clockOutAt() != null && request.clockOutAt().isBefore(request.clockInAt()))
            throw ApiException.bad("下班時間不能早於上班時間");
        // 改回「還在班」時，不能跟同一人同一天另一筆在班紀錄重複
        if (request.clockOutAt() == null && record.getClockOutAt() != null
                && shiftStaff.existsByBusinessDateAndStaffIdAndClockOutAtIsNull(record.getBusinessDate(), record.getStaffId()))
            throw ApiException.conflict("這位員工已經有一筆在班紀錄");
        record.setClockInAt(request.clockInAt());
        record.setClockOutAt(request.clockOutAt());
        record.setUpdatedBy(manager.id());
        record.setUpdatedAt(LocalDateTime.now(clock));
        return toResponse(shiftStaff.saveAndFlush(record));
    }

    public void delete(long id, String username) {
        requireManager(username);
        shiftStaff.delete(find(id));
    }

    // ---------- 共用（checklist 也會用到） ----------

    public Staff requireManager(String username) {
        var staff = orders.actor(username);
        if (!MANAGER_LEVELS.contains(staff.level()))
            throw new ApiException(HttpStatus.FORBIDDEN, "只有店長或總部可以執行這個操作");
        return staff;
    }

    /** 員工必須存在且在職；不會預設成其他人。 */
    public void requireActiveStaff(long staffId) {
        var rows = staffRow(staffId);
        if (rows.isEmpty()) throw ApiException.bad("找不到這位員工");
        if (!PosRepository.flag(rows.get(0), "active")) throw ApiException.bad("這位員工已停用");
    }

    public String staffName(long staffId) {
        var rows = staffRow(staffId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("name");
    }

    private List<Map<String, Object>> staffRow(long staffId) {
        return pos.rows("SELECT name, active FROM staff_account WHERE staff_id=?", staffId);
    }

    private ShiftStaff find(long id) {
        return shiftStaff.findById(id).orElseThrow(() -> ApiException.missing("找不到這筆打卡紀錄"));
    }

    private Response toResponse(ShiftStaff s) {
        return new Response(s.getId(), s.getBusinessDate(), s.getStaffId(), staffName(s.getStaffId()),
            s.getClockInAt(), s.getClockOutAt(), s.getClockOutAt() == null, s.getUpdatedBy(), s.getUpdatedAt());
    }
}
