# Demo phân quyền

Nhánh `fix/auth` sử dụng quyền nền từ role và giới hạn riêng theo user. Chọn một màn hình cho role sẽ cấp toàn bộ thao tác được hỗ trợ của màn hình đó. Giới hạn user chỉ thu hồi quyền, không cấp vượt role. Chặn truy cập sẽ chặn cả thao tác thuộc màn hình. ADMIN toàn hệ thống luôn toàn quyền.

## Khởi tạo dữ liệu

DataInitializer chỉ đọc `src/main/resources/seed_rbac_and_menus.sql`, chứa dữ liệu nền, migration phân quyền và tài khoản demo. Script có đánh dấu phiên bản; khởi động lại không ghi đè thay đổi quyền khi demo.

Khi `APP_SEED_DEMO=true`, cùng file SQL chuẩn bị tài khoản, đợt và 15 kịch bản đồ án. Sau khi công bố workflow nền, DemoDataSeeder tạo process và lịch sử bằng Flowable trong cùng transaction; không đọc thêm file SQL. Nếu học kỳ 3 của năm hiện tại đã có đợt khác, bỏ qua các kịch bản thay vì sửa đợt đang dùng.

Tài khoản workflow demo: `demo.student.1` đến `demo.student.15`, `demo.lecturer`, `khoa`, `committee`; mật khẩu tài khoản mới là `admin123`. Tài khoản đã tồn tại giữ mật khẩu cũ. Dữ liệu demo quyền bên dưới luôn được khởi tạo theo version, không phụ thuộc `APP_SEED_DEMO`.

| Tài khoản | Mật khẩu | Quyền demo |
| --- | --- | --- |
| demo.full | admin123 | Toàn bộ quyền được cấp cho role Khoa |
| demo.view1 | admin123 | Cùng role Khoa, chặn mọi thao tác không phải xem |
| demo.view2 | admin123 | Cùng role Khoa, chặn mọi thao tác không phải xem |

Tài khoản admin hiện có dùng để quản trị. Chọn menu Phân quyền người dùng, chọn user và phạm vi, bỏ tích quyền cần chặn rồi lưu. Có thể dùng màn Các đợt ĐATN để so sánh ba tài khoản: thao tác bị vô hiệu với demo.view1 và demo.view2, API cũng từ chối thao tác. Menu admin giữ như main, chỉ thêm Phân quyền người dùng; các menu Khoa / Bộ môn, Nhật ký thao tác và Tác vụ quy trình thêm trong nhánh auth đã được ẩn. Các mã quyền nội bộ vẫn được giữ để không làm mất giới hạn API đã lưu.

JWT xác định user; backend lấy quyền hiện tại từ DB mỗi request. Giao diện làm mới quyền mỗi 30 giây khi tab đang hiển thị, khi quay lại cửa sổ và khi nhận HTTP 403. Đổi quyền không cần đăng nhập lại. Với tác vụ/quy trình theo đợt, backend kiểm tra theo đợt của tài nguyên. Giới hạn toàn hệ thống luôn áp dụng cùng giới hạn theo đợt.

Chưa chạy ứng dụng, build, test hoặc SQL cho thay đổi này theo yêu cầu; cần kiểm tra tích hợp khi chạy demo.
