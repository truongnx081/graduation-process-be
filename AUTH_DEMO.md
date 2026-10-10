# Demo phân quyền

Nhánh `fix/auth` sử dụng quyền nền từ role và giới hạn riêng theo user. Chọn một màn hình cho role sẽ cấp toàn bộ thao tác được hỗ trợ của màn hình đó. Giới hạn user chỉ thu hồi quyền, không cấp vượt role. Chặn truy cập sẽ chặn cả thao tác thuộc màn hình. ADMIN toàn hệ thống luôn toàn quyền.

## Khởi tạo dữ liệu

DataInitializer thực hiện tuần tự, trong transaction:

1. `src/main/resources/seed_rbac_and_menus.sql`: dữ liệu nền hiện có.
2. `src/main/resources/migrate_user_permissions.sql`: bảng giới hạn quyền, phiên bản chỉnh sửa và danh mục quyền.
3. `src/main/resources/seed_auth_demo.sql`: tài khoản demo khi `APP_SEED_AUTH_DEMO=true` (mặc định).

Các script có đánh dấu phiên bản; khởi động lại không ghi đè thay đổi quyền khi demo. Khi dùng SQL thủ công, thực hiện theo đúng thứ tự trên.

| Tài khoản | Mật khẩu | Quyền demo |
| --- | --- | --- |
| demo.full | admin123 | Toàn bộ quyền được cấp cho role Khoa |
| demo.view1 | admin123 | Cùng role Khoa, chặn mọi thao tác không phải xem |
| demo.view2 | admin123 | Cùng role Khoa, chặn mọi thao tác không phải xem |

Tài khoản admin hiện có dùng để quản trị. Chọn menu Phân quyền người dùng, chọn user và phạm vi, tích Chặn riêng rồi lưu. Có thể dùng Khoa / Bộ môn để so sánh ba tài khoản: nút Thêm/Sửa/Xóa bị vô hiệu với demo.view1 và demo.view2, API cũng từ chối thao tác.

JWT xác định user; backend lấy quyền hiện tại từ DB mỗi request. Giao diện làm mới quyền mỗi 30 giây khi tab đang hiển thị, khi quay lại cửa sổ và khi nhận HTTP 403. Đổi quyền không cần đăng nhập lại. Với tác vụ/quy trình theo đợt, backend kiểm tra theo đợt của tài nguyên. Giới hạn toàn hệ thống luôn áp dụng cùng giới hạn theo đợt.

Chưa chạy ứng dụng, build, test hoặc SQL cho thay đổi này theo yêu cầu; cần kiểm tra tích hợp khi chạy demo.
