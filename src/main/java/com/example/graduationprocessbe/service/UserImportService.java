package com.example.graduationprocessbe.service;

import com.example.graduationprocessbe.dto.request.CreateUserRequest;
import com.example.graduationprocessbe.entity.Department;
import com.example.graduationprocessbe.exception.ApplicationException;
import com.example.graduationprocessbe.repository.DepartmentRepository;
import com.example.graduationprocessbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bulk account creation from an Excel sheet. Rows are validated up front (dry run) so the admin can fix the
 * file before anything is written; the real import creates each valid row through {@link UserService#createUser},
 * one transaction per row, so a single bad row never rolls back the others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserImportService {
    private static final String[] HEADERS = {"Tên đăng nhập (mã SV/GV) *", "Họ tên *", "Email *", "Điện thoại", "Loại tài khoản * (STUDENT/LECTURER)", "Mã khoa", "Mật khẩu"};
    private static final int MAX_ROWS = 2000;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserService userService;
    private final UserRepository users;
    private final DepartmentRepository departments;

    public record RowResult(int row, String username, String fullName, String email, String phone, String userType,
                            String departmentCode, String status, String message) {}

    public record ImportResult(int total, int valid, int invalid, int created, int failed, boolean dryRun, List<RowResult> rows) {}

    private record Parsed(int row, String username, String fullName, String email, String phone, String userType,
                          String departmentCode, String password, Department department, List<String> errors) {}

    public byte[] template() {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Tai khoan");
            CellStyle head = book.createCellStyle();
            Font bold = book.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(head);
                sheet.setColumnWidth(i, 26 * 256);
            }
            String[][] samples = {
                    {"2051010001", "Nguyễn Văn A", "2051010001@st.example.edu.vn", "0901234567", "STUDENT", "", ""},
                    {"gv.tranb", "Trần Thị B", "tranb@example.edu.vn", "", "LECTURER", "", "MatKhau@123"},
            };
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < samples[r].length; c++) row.createCell(c).setCellValue(samples[r][c]);
            }
            sheet.createFreezePane(0, 1);

            Sheet guide = book.createSheet("Huong dan");
            String[] notes = {
                    "Mỗi dòng là một tài khoản; xoá 2 dòng mẫu trước khi nhập.",
                    "Tên đăng nhập, Họ tên, Email, Loại tài khoản là bắt buộc. Loại tài khoản: STUDENT (sinh viên) hoặc LECTURER (giảng viên).",
                    "Mật khẩu để trống thì mặc định bằng tên đăng nhập (cần ít nhất 6 ký tự); người dùng nên đổi sau lần đăng nhập đầu.",
                    "Mã khoa (không bắt buộc) phải trùng một mã trong danh sách bên dưới.",
                    "Tên đăng nhập hoặc email đã có trong hệ thống sẽ bị bỏ qua và báo lỗi ở dòng tương ứng.",
                    "",
                    "Mã khoa | Tên khoa",
            };
            int r = 0;
            for (String note : notes) guide.createRow(r++).createCell(0).setCellValue(note);
            for (Department department : departments.findAll()) {
                Row row = guide.createRow(r++);
                row.createCell(0).setCellValue(department.getDeptCode());
                row.createCell(1).setCellValue(department.getDeptName());
            }
            guide.setColumnWidth(0, 30 * 256);
            guide.setColumnWidth(1, 40 * 256);

            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApplicationException("IMPORT_TEMPLATE_FAILED", "Không tạo được file mẫu", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public ImportResult importUsers(MultipartFile file, boolean dryRun) {
        List<Parsed> parsed = validate(read(file));
        List<RowResult> rows = new ArrayList<>();
        int valid = 0, created = 0, failed = 0;
        for (Parsed p : parsed) {
            if (!p.errors().isEmpty()) {
                rows.add(result(p, "INVALID", String.join("; ", p.errors())));
                continue;
            }
            valid++;
            if (dryRun) {
                rows.add(result(p, "VALID", null));
                continue;
            }
            try {
                userService.createUser(request(p));
                created++;
                rows.add(result(p, "CREATED", null));
            } catch (ApplicationException e) {
                failed++;
                rows.add(result(p, "FAILED", e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Import user row {} failed", p.row(), e);
                failed++;
                rows.add(result(p, "FAILED", "Không tạo được tài khoản"));
            }
        }
        return new ImportResult(parsed.size(), valid, parsed.size() - valid, created, failed, dryRun, rows);
    }

    private List<String[]> read(MultipartFile file) {
        String name = Optional.ofNullable(file == null ? null : file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (file == null || file.isEmpty()) throw bad("Chưa chọn file Excel");
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) throw bad("Chỉ hỗ trợ file Excel (.xlsx, .xls)");
        DataFormatter formatter = new DataFormatter();
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook book = WorkbookFactory.create(in)) {
            Sheet sheet = book.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                String[] values = new String[HEADERS.length + 1];
                values[HEADERS.length] = String.valueOf(i + 1);
                boolean blank = true;
                for (int c = 0; c < HEADERS.length; c++) {
                    String value = row == null ? "" : formatter.formatCellValue(row.getCell(c)).trim();
                    values[c] = value;
                    if (!value.isEmpty()) blank = false;
                }
                if (blank) continue;
                if (rows.size() >= MAX_ROWS) throw bad("Mỗi lần nhập tối đa " + MAX_ROWS + " dòng");
                rows.add(values);
            }
        } catch (ApplicationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw bad("Không đọc được file Excel, vui lòng dùng file mẫu");
        }
        if (rows.isEmpty()) throw bad("File không có dòng dữ liệu nào");
        return rows;
    }

    private List<Parsed> validate(List<String[]> rows) {
        Map<String, Department> departmentByCode = new HashMap<>();
        Map<String, Integer> usernames = new HashMap<>();
        Map<String, Integer> emails = new HashMap<>();
        List<Parsed> result = new ArrayList<>();
        for (String[] v : rows) {
            int rowNumber = Integer.parseInt(v[HEADERS.length]);
            List<String> errors = new ArrayList<>();
            String username = v[0], fullName = v[1], email = v[2], phone = v[3], departmentCode = v[5], password = v[6];
            String userType = userType(v[4]);

            if (username.isEmpty()) errors.add("Thiếu tên đăng nhập");
            else if (username.length() > 50) errors.add("Tên đăng nhập quá 50 ký tự");
            else if (username.contains(" ")) errors.add("Tên đăng nhập không được có khoảng trắng");
            else {
                Integer first = usernames.putIfAbsent(username.toLowerCase(Locale.ROOT), rowNumber);
                if (first != null) errors.add("Trùng tên đăng nhập với dòng " + first);
                else if (users.existsByUsername(username)) errors.add("Tên đăng nhập đã tồn tại");
            }
            if (fullName.isEmpty()) errors.add("Thiếu họ tên");
            else if (fullName.length() > 100) errors.add("Họ tên quá 100 ký tự");
            if (email.isEmpty()) errors.add("Thiếu email");
            else if (email.length() > 120 || !EMAIL.matcher(email).matches()) errors.add("Email không hợp lệ");
            else {
                Integer first = emails.putIfAbsent(email.toLowerCase(Locale.ROOT), rowNumber);
                if (first != null) errors.add("Trùng email với dòng " + first);
                else if (users.existsByEmail(email)) errors.add("Email đã tồn tại");
            }
            if (phone.length() > 30) errors.add("Điện thoại quá 30 ký tự");
            if (userType == null) errors.add("Loại tài khoản phải là STUDENT hoặc LECTURER");

            Department department = null;
            if (!departmentCode.isEmpty()) {
                department = departmentByCode.computeIfAbsent(departmentCode.toLowerCase(Locale.ROOT),
                        code -> departments.findByDeptCodeIgnoreCase(departmentCode).orElse(null));
                if (department == null) errors.add("Mã khoa không tồn tại");
            }
            if (password.isEmpty()) password = username;
            if (password.length() < 6 || password.length() > 100)
                errors.add(v[6].isEmpty() ? "Mật khẩu trống sẽ lấy theo tên đăng nhập nhưng cần ít nhất 6 ký tự" : "Mật khẩu phải từ 6 đến 100 ký tự");

            result.add(new Parsed(rowNumber, username, fullName, email, phone, userType == null ? v[4] : userType,
                    departmentCode, password, department, errors));
        }
        return result;
    }

    private static String userType(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.equals("STUDENT") || value.equals("SINH VIÊN") || value.equals("SV")) return "STUDENT";
        if (value.equals("LECTURER") || value.equals("GIẢNG VIÊN") || value.equals("GV")) return "LECTURER";
        return null;
    }

    private static CreateUserRequest request(Parsed p) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(p.username());
        request.setFullName(p.fullName());
        request.setEmail(p.email());
        request.setPhone(p.phone().isEmpty() ? null : p.phone());
        request.setPassword(p.password());
        request.setUserType(p.userType());
        if (p.department() != null) request.setDepartmentId(p.department().getId());
        return request;
    }

    private static RowResult result(Parsed p, String status, String message) {
        return new RowResult(p.row(), p.username(), p.fullName(), p.email(), p.phone(), p.userType(), p.departmentCode(), status, message);
    }

    private static ApplicationException bad(String message) {
        return new ApplicationException("INVALID_IMPORT_FILE", message, HttpStatus.BAD_REQUEST);
    }
}
